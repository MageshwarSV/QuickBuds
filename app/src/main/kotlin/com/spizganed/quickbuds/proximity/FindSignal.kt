package com.spizganed.quickbuds.proximity

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The Find nearby signal chain, pure (no Android): [com.spizganed.quickbuds.ui.FindNearbyActivity] feeds it readings
 * with their time; tests feed it numbers.
 *
 *     raw RSSI -> validation -> rolling median -> exponential average -> stability -> proximity state -> screen
 *
 * Three things stay apart: the signal (what Bluetooth measured), the proximity (a rough inference from it) and the
 * direction, which ordinary Bluetooth signal strength does not give at all: nothing here claims one.
 */

/** Where readings come from: each has its own scale and its own limits. */
enum class FindSource(val key: String) {
    /** The device's own Google Fast Pair broadcast ([BroadcastTracker]): always dBm. */
    BROADCAST("ble"),
    /** `readRemoteRssi` on the connection: dBm, or the golden range on some phones (0 = about 1 m or closer). */
    LINK("link")
}

/** Every number the chain uses, in one place. Defaults measured 2026-10-10 on a Nord CE5 with Nord Buds 3r. */
data class FindConfig(
    val medianWindow: Int = 5,
    val alpha: Double = 0.25,
    /** Readings before a state is given: until then "Calibrating signal". At least a full median window. */
    val minSamples: Int = 5,
    /** Readings for the stability score. */
    val stabilityWindow: Int = 10,
    /** Standard deviation in dB: up to [stableSd] stable, up to [fluctuatingSd] fluctuating, above unstable. */
    val stableSd: Double = 4.0,
    val fluctuatingSd: Double = 8.0,
    /** A source silent this long is stale. */
    val staleMs: Long = 3_000,
    /** Graph and trend history. */
    val historyMs: Long = 30_000,
    val historyMax: Int = 300,
    /** Trend: readings within [trendWindowMs], at least [trendMinSamples] over [trendMinSpanMs], changing [trendDb]. */
    val trendWindowMs: Long = 8_000,
    val trendMinSamples: Int = 5,
    val trendMinSpanMs: Long = 4_000,
    val trendDb: Double = 3.0,
    /** A new proximity state needs this many updates in a row. */
    val confirmUpdates: Int = 3
)

/** One valid reading, raw and after the filters, for the graph and diagnostics. */
data class FindSample(val atMs: Long, val raw: Int, val filtered: Double)

enum class Stability { STABLE, FLUCTUATING, UNSTABLE }

/**
 * Validation and filtering of one source. A rolling median drops a lone spike; the exponential average after it
 * smooths what is left. A sustained change passes: the median follows it once most of its window agrees.
 * Weak readings are valid (far away is exactly when they matter); only values the API cannot report are dropped.
 *
 * The average starts only once the median window is full, from that median: one first reading never sets it.
 * Stability is the noise around the trend (the scatter about a straight line through the last readings), so walking
 * toward the device is a trend, not instability. Was: the spread about the mean, which made every approach unstable.
 */
class RssiProcessor(val scale: RssiScale, private val config: FindConfig = FindConfig()) {

    private val window = ArrayDeque<Int>()
    /** (time, raw) of the last readings, for the stability score. */
    private val recent = ArrayDeque<Pair<Long, Int>>()
    private val history = ArrayDeque<FindSample>()

    var filtered: Double? = null
        private set
    var median: Double? = null
        private set
    var count = 0
        private set
    var lastAt = Long.MIN_VALUE
        private set
    var lastRaw: Int? = null
        private set

    /** Adds a reading; false (and nothing changes) when the API could not have reported it. */
    fun add(raw: Int, nowMs: Long): Boolean {
        if (!valid(raw)) return false
        window.addLast(raw)
        if (window.size > config.medianWindow) window.removeFirst()
        val m = window.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0 }
        median = m
        recent.addLast(nowMs to raw)
        if (recent.size > config.stabilityWindow) recent.removeFirst()
        count++
        lastAt = nowMs
        lastRaw = raw
        // No average from a partial window: it would carry the first reading's weight for a long time.
        if (count < config.medianWindow) return true
        val f = filtered?.let { config.alpha * m + (1 - config.alpha) * it } ?: m
        filtered = f
        history.addLast(FindSample(nowMs, raw, f))
        while (history.size > config.historyMax || (history.isNotEmpty() && nowMs - history.first().atMs > config.historyMs)) history.removeFirst()
        return true
    }

    fun valid(raw: Int): Boolean = when (scale) {
        // 127 is Android's "not available"; a dBm reading above 0 is not a received signal.
        RssiScale.DBM -> raw in -127..0
        // Golden range: dB under (negative) or above (positive) the range; above +20 does not happen.
        RssiScale.GOLDEN_RANGE -> raw in -127..20
    }

    /** Enough readings for a state, and the last one recent. */
    fun ready(nowMs: Long) = count >= maxOf(config.minSamples, config.medianWindow) && filtered != null && !stale(nowMs)

    fun stale(nowMs: Long) = count == 0 || nowMs - lastAt > config.staleMs

    /** Standard deviation of the last raw readings about their least-squares line, null with fewer than three. */
    fun spread(): Double? {
        if (recent.size < 3) return null
        val t0 = recent.first().first
        val xs = recent.map { (it.first - t0) / 1000.0 }
        val ys = recent.map { it.second.toDouble() }
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) { num += (xs[i] - mx) * (ys[i] - my); den += (xs[i] - mx) * (xs[i] - mx) }
        val slope = if (den == 0.0) 0.0 else num / den
        val residuals = xs.indices.map { ys[it] - (my + slope * (xs[it] - mx)) }
        return sqrt(residuals.sumOf { it * it } / residuals.size)
    }

    fun stability(): Stability? = spread()?.let {
        when {
            it <= config.stableSd -> Stability.STABLE
            it <= config.fluctuatingSd -> Stability.FLUCTUATING
            else -> Stability.UNSTABLE
        }
    }

    /** The graph's samples, oldest first, within [FindConfig.historyMs]. */
    fun samples(nowMs: Long): List<FindSample> = history.filter { nowMs - it.atMs <= config.historyMs }

    fun reset() {
        window.clear(); recent.clear(); history.clear()
        filtered = null; median = null; count = 0; lastAt = Long.MIN_VALUE; lastRaw = null
    }
}

enum class Trend { IMPROVING, WEAKENING, STEADY, FLUCTUATING, UNKNOWN }

/**
 * Whether the signal is getting stronger or weaker: a least-squares line through the filtered readings of the last
 * [FindConfig.trendWindowMs]. One reading never decides; a change under [FindConfig.trendDb] across the window is
 * steady; an unstable signal is "fluctuating", not a trend. It says nothing about direction.
 */
object SignalTrendAnalyzer {

    fun trend(samples: List<FindSample>, stability: Stability?, nowMs: Long, config: FindConfig = FindConfig()): Trend {
        val pts = samples.filter { nowMs - it.atMs <= config.trendWindowMs }
        if (pts.size < config.trendMinSamples || pts.last().atMs - pts.first().atMs < config.trendMinSpanMs) return Trend.UNKNOWN
        if (stability == Stability.UNSTABLE) return Trend.FLUCTUATING
        val t0 = pts.first().atMs
        val xs = pts.map { (it.atMs - t0) / 1000.0 }
        val ys = pts.map { it.filtered }
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) { num += (xs[i] - mx) * (ys[i] - my); den += (xs[i] - mx) * (xs[i] - mx) }
        if (den == 0.0) return Trend.UNKNOWN
        val change = num / den * (xs.last() - xs.first())
        return when {
            change >= config.trendDb -> Trend.IMPROVING
            change <= -config.trendDb -> Trend.WEAKENING
            else -> Trend.STEADY
        }
    }
}

enum class ProximityLevel {
    /** Nothing heard yet, shortly after the screen opened. */
    SEARCHING,
    /** Readings coming in, not enough for a state. */
    CALIBRATING,
    FAR,
    NEARBY,
    VERY_CLOSE,
    SIGNAL_UNSTABLE,
    /** Readings stopped, or none came at all. */
    NO_SIGNAL,
    /** The connection to the device is gone and it broadcasts nothing. */
    DEVICE_DISCONNECTED,
    BLUETOOTH_OFF
}

/**
 * The levels that split the states, in the source's own units, around [reference]: the level 1 m away. Entering a
 * state takes a stronger signal than staying in it (hysteresis), so a signal that hovers at an edge does not flip.
 */
data class FindThresholds(
    val reference: Double,
    val veryCloseEnter: Double,
    val veryCloseExit: Double,
    val nearbyEnter: Double,
    val nearbyExit: Double
) {
    companion object {
        /**
         * dBm. Measured: about -44 dBm beside the phone, -52 to -63 at about 1 m, -70 a few metres away, for a
         * stated 1 m level of -52.
         */
        fun dbm(reference: Double) = FindThresholds(reference, reference + 7, reference + 3, reference - 10, reference - 14)

        /** Golden range: 0 within about 1 m; measured up to +9 touching, -6 to -10 a few metres away. */
        val GOLDEN = FindThresholds(0.0, 4.0, 1.0, -6.0, -9.0)
    }
}

/**
 * Proximity from the filtered signal, with evidence before any change: a new state must hold for
 * [FindConfig.confirmUpdates] updates in a row. "Very close" also needs a signal that is not unstable: one strong
 * reading, or being connected, is never "here". Losing the signal is immediate: no proof is needed that nothing came.
 */
class ProximityClassifier(private val config: FindConfig = FindConfig()) {

    var level = ProximityLevel.SEARCHING
        private set
    /** Why the last change happened, for diagnostics. */
    var reason = "start"
        private set
    private var candidate: ProximityLevel? = null
    private var candidateCount = 0

    /**
     * One update. [filtered] null: no fresh reading. [signalEverSeen]: something came since the screen opened.
     * [linkUp]: the connection still answers. [bluetoothOn]: the adapter is on.
     */
    fun update(
        filtered: Double?, ready: Boolean, stability: Stability?, thresholds: FindThresholds?,
        signalEverSeen: Boolean, searchingMs: Long, linkUp: Boolean, bluetoothOn: Boolean
    ): ProximityLevel {
        if (!bluetoothOn) return set(ProximityLevel.BLUETOOTH_OFF, "Bluetooth off")
        if (filtered == null) {
            return when {
                !linkUp && signalEverSeen -> set(ProximityLevel.DEVICE_DISCONNECTED, "no connection, no broadcast")
                !signalEverSeen && searchingMs < SEARCH_GRACE_MS -> set(ProximityLevel.SEARCHING, "nothing yet")
                else -> set(ProximityLevel.NO_SIGNAL, "no fresh reading")
            }
        }
        if (!ready || thresholds == null) return set(ProximityLevel.CALIBRATING, "collecting readings")
        val target = when {
            stability == Stability.UNSTABLE -> ProximityLevel.SIGNAL_UNSTABLE
            filtered >= (if (level == ProximityLevel.VERY_CLOSE) thresholds.veryCloseExit else thresholds.veryCloseEnter) -> ProximityLevel.VERY_CLOSE
            filtered >= (if (level == ProximityLevel.NEARBY || level == ProximityLevel.VERY_CLOSE) thresholds.nearbyExit else thresholds.nearbyEnter) ->
                ProximityLevel.NEARBY
            else -> ProximityLevel.FAR
        }
        // From a state with no reading to judge, the first judgement needs no streak, except "very close": that is
        // never given on fresh readings alone. It starts as nearby, with the streak already counting.
        val judged = level in JUDGED
        if (target == level) { candidate = null; candidateCount = 0; return level }
        if (!judged) {
            if (target != ProximityLevel.VERY_CLOSE) return set(target, "first reading %.1f".format(filtered))
            set(ProximityLevel.NEARBY, "first reading %.1f, very close not yet proven".format(filtered))
            candidate = ProximityLevel.VERY_CLOSE
            candidateCount = 1
            return level
        }
        if (candidate != target) { candidate = target; candidateCount = 0 }
        candidateCount++
        if (candidateCount >= config.confirmUpdates) return set(target, "%d updates at %.1f".format(candidateCount, filtered))
        return level
    }

    fun reset() {
        level = ProximityLevel.SEARCHING; reason = "reset"; candidate = null; candidateCount = 0
    }

    private fun set(l: ProximityLevel, why: String): ProximityLevel {
        if (l != level) reason = why
        level = l
        candidate = null
        candidateCount = 0
        return l
    }

    companion object {
        /** How long "Searching" lasts before "not detected". */
        const val SEARCH_GRACE_MS = 8_000L
        private val JUDGED = setOf(ProximityLevel.FAR, ProximityLevel.NEARBY, ProximityLevel.VERY_CLOSE, ProximityLevel.SIGNAL_UNSTABLE)
    }
}

/**
 * Distance, worked out on its own from every reading: the log-distance model
 * `d = 10 ^ ((reference - filtered) / (10 * n))` on the 1 m level the device states itself in its broadcast (its
 * transmit power, a factory value: [BroadcastDistance]). No setup and no calibration step; the screen calls it
 * approximate. Kept within [MIN_M]..[MAX_M]: beyond that a number would claim more than the signal holds. On the
 * golden range (connection only), the level maps to dBm under its lower edge ([GOLDEN_LOWER_DBM]): far bands only,
 * since everything within about 1 m reads 0 there.
 */
object DistanceEstimate {
    /** Indoors with walls and bodies: between free space (2) and a crowded room. */
    const val EXPONENT = 2.2
    const val MIN_M = 0.5
    /** Bluetooth's useful range indoors; beyond it the signal is noise. */
    const val MAX_M = 30.0

    /** Metres, at least [MIN_M]; null beyond [MAX_M] ("more than 30 m"). */
    fun metres(reference: Double, filtered: Double): Double? {
        val d = 10.0.pow((reference - filtered) / (10 * EXPONENT))
        return if (d > MAX_M) null else d.coerceAtLeast(MIN_M)
    }

    /** What the screen shows: half metres under 5 m, whole metres above. */
    fun rounded(metres: Double): Double = if (metres < 5) (metres * 2).roundToInt() / 2.0 else metres.roundToInt().toDouble()

    /** The golden range's lower edge, at most -56 dBm (Bluetooth Core, Vol 2 Part A, 3.7): golden 0 reads as this. */
    const val GOLDEN_LOWER_DBM = -60.0

    /** A golden-range level as dBm, for the far bands: 0 and above stay at the lower edge (about 1 m). */
    fun goldenAsDbm(level: Double) = GOLDEN_LOWER_DBM + minOf(level, 0.0)
}

/**
 * The distance bands the screen names and the radar rings show, from far to here:
 * 21-30 m too weak, 11-20 m getting closer, 5-10 m better, 1-5 m near, 1 m and closer reached.
 * A band changes only [margin] past its edge, so a distance hovering at an edge does not flicker.
 */
enum class DistanceBand(val upToM: Double) {
    REACHED(1.0), NEAR(5.0), BETTER(10.0), CLOSER(20.0), WEAK(30.0), BEYOND(Double.MAX_VALUE);

    companion object {
        /** Ring radii for the radar, in metres. */
        val RINGS = listOf(1.0, 5.0, 10.0, 20.0, 30.0)

        fun of(metres: Double?, previous: DistanceBand?, margin: Double = 0.15): DistanceBand {
            val plain = if (metres == null) BEYOND else values().first { metres <= it.upToM }
            if (previous == null || plain == previous || metres == null) return plain
            // Leave the previous band only clearly past its edge.
            val lower = values().getOrNull(previous.ordinal - 1)?.upToM ?: 0.0
            val inside = metres > lower * (1 - margin) && metres <= previous.upToM * (1 + margin)
            return if (inside) previous else plain
        }
    }
}

/**
 * Play sound on the earbuds: success only when the earbuds answer. The find tone's reply is `8400 00`
 * (PROTOCOL.md, "Find my earbuds"); a command sent is not a sound played.
 */
class FindSound(private val timeoutMs: Long = 3_000) {

    enum class State { UNSUPPORTED, DISCONNECTED, IDLE, PENDING, PLAYING, FAILED }

    var state = State.IDLE
        private set
    /** What the pending request asked for: on or off. */
    private var wanted = false
    private var sentAt = 0L

    /** The earbuds' capability and link: no find tone, or no connection, and the button says so. */
    fun onCapability(supported: Boolean, connected: Boolean) {
        state = when {
            !supported -> State.UNSUPPORTED
            !connected -> State.DISCONNECTED
            state == State.UNSUPPORTED || state == State.DISCONNECTED -> State.IDLE
            else -> state
        }
    }

    /** The button: true when a command should go out now. A second press while one is pending sends nothing. */
    fun request(on: Boolean, nowMs: Long): Boolean {
        if (state == State.UNSUPPORTED || state == State.DISCONNECTED || state == State.PENDING) return false
        wanted = on
        sentAt = nowMs
        state = State.PENDING
        return true
    }

    /** The earbuds' reply to the find tone command: [accepted] when its status byte is 0. */
    fun onAck(accepted: Boolean) {
        if (state != State.PENDING) return
        state = if (!accepted) State.FAILED else if (wanted) State.PLAYING else State.IDLE
    }

    /** No reply in time: the command did not land. */
    fun tick(nowMs: Long) {
        if (state == State.PENDING && nowMs - sentAt >= timeoutMs) state = State.FAILED
    }

    val playing get() = state == State.PLAYING

    /** The tone was switched elsewhere (the loss warning's card): this follows it. */
    fun syncPlaying(on: Boolean) {
        if (state == State.PENDING || state == State.UNSUPPORTED || state == State.DISCONNECTED) return
        state = if (on) State.PLAYING else State.IDLE
    }
}

/**
 * The link's scale, from its first three readings: a median of -30 or more is the golden range (dBm that strong means
 * touching), else dBm. Corrected either way when a reading proves it wrong: a golden-range value under -40 does not
 * happen, nor a link in dBm above -10. Readings before the scale is known feed nothing.
 */
class LinkScale {
    var scale: RssiScale? = null
        private set
    private val first = ArrayList<Int>()

    /** One raw reading; true when the scale was set or changed (the caller starts its processor over). */
    fun onReading(raw: Int): Boolean {
        if (raw == 127) return false
        when (scale) {
            null -> {
                first += raw
                if (first.size < 3) return false
                scale = if (first.sorted()[1] >= -30) RssiScale.GOLDEN_RANGE else RssiScale.DBM
                return true
            }
            RssiScale.GOLDEN_RANGE -> if (raw < -40) { scale = RssiScale.DBM; return true }
            RssiScale.DBM -> if (raw > -10) { scale = RssiScale.GOLDEN_RANGE; return true }
        }
        return false
    }

    fun reset() { scale = null; first.clear() }
}

/**
 * Whether the phone is swung around too fast for the signal to follow: the linear acceleration (gravity removed),
 * smoothed, with hysteresis. Walking slowly stays under [enter]; swinging the phone goes over it.
 */
class MotionMeter(private val enter: Double = 3.0, private val exit: Double = 1.5, private val alpha: Double = 0.2) {
    var smoothed = 0.0
        private set
    var fast = false
        private set

    /** One sensor event: the acceleration's magnitude in m/s2. */
    fun add(magnitude: Double): Boolean {
        smoothed = alpha * magnitude + (1 - alpha) * smoothed
        fast = if (fast) smoothed > exit else smoothed > enter
        return fast
    }

    fun reset() { smoothed = 0.0; fast = false }
}

/**
 * What to tell the user now, as a person guiding them would: wait, hold steady, slow down, closer, weaker, try
 * elsewhere, right here. From the state, the trend and the phone's motion; one message at a time, kept at least
 * [holdMs] so the line does not flicker, except an urgent one (lost, right here, slow down), which shows at once.
 */
class SearchCoach(private val holdMs: Long = 3_000, private val noChangeMs: Long = 15_000) {

    enum class Tip { SEARCHING, WAIT, STEADY, SLOW, CLOSER, WEAKER, TRY_OTHER, NEARBY, HERE_PLAY, HERE, LOST, FAR, NONE }

    var tip = Tip.NONE
        private set
    private var shownAt = Long.MIN_VALUE / 2
    /** Since when nothing has changed (trend, state, an urgent tip): the clock of "try another way". */
    private var trendSince = 0L
    private var lastTrend: Trend? = null
    private var lastLevel: ProximityLevel? = null

    /**
     * [near]: the distance band says close (1-5 m or nearer), far, or null without one; it picks the close/weak tip,
     * so the tip never contradicts the band under the distance. [turning]: the user is turning for the arrow; the
     * trend tips (closer, weaker, try elsewhere) would contradict that, so the arrow's own line guides alone.
     */
    fun update(
        level: ProximityLevel, trend: Trend, movingFast: Boolean, canPlay: Boolean, signalEverSeen: Boolean, nowMs: Long,
        near: Boolean? = null, turning: Boolean = false
    ): Tip {
        if (trend != lastTrend) { lastTrend = trend; trendSince = nowMs }
        if (level != lastLevel) { lastLevel = level; trendSince = nowMs }
        val want = when {
            level == ProximityLevel.BLUETOOTH_OFF -> Tip.NONE
            level == ProximityLevel.SEARCHING -> Tip.SEARCHING
            level == ProximityLevel.NO_SIGNAL || level == ProximityLevel.DEVICE_DISCONNECTED -> if (signalEverSeen) Tip.LOST else Tip.SEARCHING
            level == ProximityLevel.CALIBRATING -> Tip.WAIT
            movingFast -> Tip.SLOW
            level == ProximityLevel.VERY_CLOSE -> if (canPlay) Tip.HERE_PLAY else Tip.HERE
            level == ProximityLevel.SIGNAL_UNSTABLE || trend == Trend.FLUCTUATING -> Tip.STEADY
            turning -> Tip.NONE
            trend == Trend.IMPROVING -> Tip.CLOSER
            trend == Trend.WEAKENING -> Tip.WEAKER
            trend == Trend.STEADY && level == ProximityLevel.FAR && near != true && nowMs - trendSince >= noChangeMs -> Tip.TRY_OTHER
            near == true || (near == null && level == ProximityLevel.NEARBY) -> Tip.NEARBY
            near == false || level == ProximityLevel.FAR -> Tip.FAR
            else -> tip
        }
        // After an urgent or steadying moment, "no change for a while" starts counting again.
        if (want == Tip.SLOW || want == Tip.STEADY || want == Tip.HERE || want == Tip.HERE_PLAY) trendSince = nowMs
        if (want != tip && (want in URGENT || nowMs - shownAt >= holdMs)) {
            tip = want
            shownAt = nowMs
        }
        return tip
    }

    fun reset() { tip = Tip.NONE; shownAt = Long.MIN_VALUE / 2; lastTrend = null; lastLevel = null }

    private companion object {
        val URGENT = setOf(Tip.LOST, Tip.HERE, Tip.HERE_PLAY, Tip.SLOW, Tip.NONE)
    }
}

/**
 * A direction arrow needs real direction measurements: ultra-wideband or Bluetooth Channel Sounding, on the phone
 * AND on the earbuds. Signal strength and the compass do not give one. No earbuds QuickBuds knows of offer ranging,
 * so [peerRanging] is false for all of them today, and the screen shows proximity only.
 */
object DirectionSupport {
    fun available(phoneUwb: Boolean, phoneChannelSounding: Boolean, peerRanging: Boolean): Boolean =
        peerRanging && (phoneUwb || phoneChannelSounding)
}
