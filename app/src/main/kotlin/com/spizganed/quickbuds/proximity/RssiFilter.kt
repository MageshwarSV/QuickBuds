package com.spizganed.quickbuds.proximity

/**
 * Smooths raw RSSI readings and measures their trend. One reading never decides anything:
 *
 *  - [smoothed]: the median of the last three readings (a lone spike, like -22 between zeros, is dropped),
 *    then an exponential moving average ([ProximityConfig.EMA_ALPHA]) of that;
 *  - [slopePer10s]: least-squares slope of the smoothed level over [ProximityConfig.TREND_WINDOW_MS];
 *  - [baseline]: the strongest smoothed level over [ProximityConfig.BASELINE_WINDOW_MS], what "moved away"
 *    is measured from;
 *  - [scale]: dBm or golden range ([RssiScale]), from the median of the first three readings.
 */
class RssiFilter(
    private val alpha: Double = ProximityConfig.EMA_ALPHA,
    /** Known scale (a BLE broadcast is always dBm): no detection, which would take -25 dBm beside the phone for golden range. */
    private val fixedScale: RssiScale? = null
) {

    var scale: RssiScale? = fixedScale
        private set
    var smoothed: Double = Double.NaN
        private set
    var count = 0
        private set

    private val first = ArrayList<Int>(SCALE_SAMPLES)
    /** The last [MEDIAN_OF] raw readings, for the spike filter. */
    private val recent = ArrayDeque<Int>()
    /** (time, smoothed) pairs, oldest first, at most [ProximityConfig.BASELINE_WINDOW_MS] old. */
    private val history = ArrayDeque<Pair<Long, Double>>()

    val ready: Boolean get() = scale != null && count >= ProximityConfig.MIN_SAMPLES

    /** Adds a reading; false when it is not a plausible RSSI (127 is Android's "not available"). */
    fun add(raw: Int, nowMs: Long): Boolean {
        if (raw > MAX_PLAUSIBLE || raw < MIN_PLAUSIBLE) return false
        if (scale == RssiScale.GOLDEN_RANGE && raw < GOLDEN_FLOOR) {
            // A golden-range value this low does not happen: the controller reports dBm after all.
            reset()
            scale = RssiScale.DBM
        }
        if (scale == null && fixedScale == null) {
            first += raw
            if (first.size >= SCALE_SAMPLES) scale = if (first.sorted()[first.size / 2] >= GOLDEN_CEILING) RssiScale.GOLDEN_RANGE else RssiScale.DBM
        }
        recent.addLast(raw)
        if (recent.size > MEDIAN_OF) recent.removeFirst()
        val median = recent.sorted()[recent.size / 2].toDouble()
        smoothed = if (count == 0) median else alpha * median + (1 - alpha) * smoothed
        count++
        history.addLast(nowMs to smoothed)
        while (history.isNotEmpty() && nowMs - history.first().first > ProximityConfig.BASELINE_WINDOW_MS) history.removeFirst()
        return true
    }

    /** dB per 10 s over the trend window, 0 with fewer than three points. Negative = getting weaker. */
    fun slopePer10s(nowMs: Long): Double {
        val pts = history.filter { nowMs - it.first <= ProximityConfig.TREND_WINDOW_MS }
        if (pts.size < 3) return 0.0
        val t0 = pts.first().first
        val xs = pts.map { (it.first - t0) / 1000.0 }
        val ys = pts.map { it.second }
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            num += (xs[i] - mx) * (ys[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        return if (den == 0.0) 0.0 else num / den * 10.0
    }

    /** The strongest smoothed level in the baseline window. */
    fun baseline(): Double = history.maxOfOrNull { it.second } ?: Double.NaN

    fun reset() {
        scale = fixedScale
        smoothed = Double.NaN
        count = 0
        first.clear()
        recent.clear()
        history.clear()
    }

    private companion object {
        const val SCALE_SAMPLES = 3
        const val MEDIAN_OF = 3
        const val MAX_PLAUSIBLE = 20
        const val MIN_PLAUSIBLE = -127
        /** A median at or above this is golden range: dBm this high means touching the phone. */
        const val GOLDEN_CEILING = -30
        const val GOLDEN_FLOOR = -40
    }
}
