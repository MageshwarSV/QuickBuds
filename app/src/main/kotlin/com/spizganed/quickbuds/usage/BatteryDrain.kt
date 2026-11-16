package com.spizganed.quickbuds.usage

import android.content.Context
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.ui.ThemeRes
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** One stretch of listening in which the level went down: from the first drop seen to the last one. */
data class DrainRun(val endMs: Long, val dropPct: Int, val durationMs: Long)

/** One month of closed runs ([BatteryDrain.addRun]): the drop and the listening time added up. */
data class MonthDrain(val dropPct: Int, val durationMs: Long)

/** One device's drain history and the stretch now being measured. */
data class DrainState(
    val runs: List<DrainRun> = emptyList(),
    val lastLevel: Int = -1,
    val firstDropLevel: Int = -1,
    val firstDropMs: Long = 0L,
    val lastDropLevel: Int = -1,
    val lastDropMs: Long = 0L,
    /** The buds in use for this stretch ([BatteryDrain.budsInUse]); 0 when not known. */
    val buds: Int = 0
)

/**
 * Battery life from history: how fast the buds' level falls while in use, so "how long will this charge last".
 *
 * Buds report the level in steps (1, 5 or 10%). A stretch is measured from the first drop seen to the last one,
 * both at the moment the new step was reported: 90% at 10:00, then 60% at 11:30 is 30% in 1 h 30 min. Starting at
 * the connect would count part of a step that began earlier. A stretch ends on charging, a bud into the case, a
 * level that went up, another set of buds in use, or a disconnect.
 */
object BatteryDrain {
    /** Runs kept per device. */
    const val MAX_RUNS = 100
    /** Only runs that ended within this many days count. */
    const val WINDOW_MS = 60L * 24 * 60 * 60_000L
    /** An estimate needs this much measured listening and this much drop. */
    const val MIN_LISTENED_MS = 30 * 60_000L
    const val MIN_DROP_PCT = 5
    /** Shorter runs are reporting noise (a level corrected right after connect). */
    const val MIN_RUN_MS = 5 * 60_000L

    /**
     * A level report. [draining]: in use, out of the case and not charging. [buds]: the buds in use
     * ([budsInUse]), 0 when not known. Another set ends the stretch and starts a new one at [level]: the level is
     * now another bud's. Was: a lower bud out of the case later counted the gap as drain.
     */
    fun onLevel(s: DrainState, nowMs: Long, level: Int, draining: Boolean, buds: Int = 0): DrainState {
        if (!draining || level < 0) return close(s, nowMs)
        if (buds != 0 && s.buds != 0 && buds != s.buds) return close(s, nowMs).copy(lastLevel = level, buds = buds)
        val t = if (buds != 0) s.copy(buds = buds) else s
        if (t.lastLevel < 0) return t.copy(lastLevel = level)
        if (level > t.lastLevel) return close(t, nowMs).copy(lastLevel = level, buds = t.buds)   // charged in between
        if (level == t.lastLevel) return t
        return if (t.firstDropLevel < 0) t.copy(lastLevel = level, firstDropLevel = level, firstDropMs = nowMs,
            lastDropLevel = level, lastDropMs = nowMs)
        else t.copy(lastLevel = level, lastDropLevel = level, lastDropMs = nowMs)
    }

    /** The link went down: the stretch ends (what happened while disconnected is not known). */
    fun onDisconnected(s: DrainState, nowMs: Long) = close(s, nowMs)

    private fun close(s: DrainState, nowMs: Long): DrainState {
        val drop = s.firstDropLevel - s.lastDropLevel
        val duration = s.lastDropMs - s.firstDropMs
        val runs = if (s.firstDropLevel >= 0 && drop > 0 && duration >= MIN_RUN_MS)
            (s.runs + DrainRun(s.lastDropMs, drop, duration)).takeLast(MAX_RUNS) else s.runs
        return DrainState(runs = runs.filter { nowMs - it.endMs <= WINDOW_MS })
    }

    /** Percent per hour while listening, and how much listening it rests on. */
    data class Rate(val pctPerHour: Double, val listenedMs: Long)

    /** The average over the runs of the last [WINDOW_MS] and the stretch now open; null until there is enough. */
    fun rate(s: DrainState, nowMs: Long): Rate? {
        val open = if (s.firstDropLevel >= 0 && s.lastDropMs > s.firstDropMs)
            listOf(DrainRun(s.lastDropMs, s.firstDropLevel - s.lastDropLevel, s.lastDropMs - s.firstDropMs)) else emptyList()
        val runs = (s.runs + open).filter { nowMs - it.endMs <= WINDOW_MS }
        val ms = runs.sumOf { it.durationMs }
        val drop = runs.sumOf { it.dropPct }
        if (ms < MIN_LISTENED_MS || drop < MIN_DROP_PCT) return null
        return Rate(drop * 3_600_000.0 / ms, ms)
    }

    /** How long [levelPct] lasts at [rate]. */
    fun lastsMs(levelPct: Int, rate: Rate): Long = (levelPct / rate.pctPerHour * 3_600_000.0).toLong()

    // --- Battery health: one sum per month, kept for [MAX_MONTHS] (the runs go after [WINDOW_MS]) ---

    /** Months kept per device, the month of now included. */
    const val MAX_MONTHS = 24
    /** Months the health chart shows. */
    const val SHOWN_MONTHS = 12

    /** The month of [ms] in [zone], as "yyyy-MM". */
    fun monthOf(ms: Long, zone: ZoneId): String = YearMonth.from(Instant.ofEpochMilli(ms).atZone(zone)).toString()

    /**
     * [months] with [run] added to the month of its end. Months older than [MAX_MONTHS] before the month of
     * [nowMs] go.
     */
    fun addRun(months: Map<String, MonthDrain>, run: DrainRun, nowMs: Long, zone: ZoneId): Map<String, MonthDrain> {
        val key = monthOf(run.endMs, zone)
        val m = months[key] ?: MonthDrain(0, 0L)
        val oldest = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(zone)).minusMonths(MAX_MONTHS - 1L).toString()
        return (months + (key to MonthDrain(m.dropPct + run.dropPct, m.durationMs + run.durationMs)))
            .filterKeys { it >= oldest }
    }

    /** The months of [runs]: the first summary, from the runs kept before it existed. */
    fun seed(runs: List<DrainRun>, nowMs: Long, zone: ZoneId): Map<String, MonthDrain> =
        runs.fold(emptyMap()) { m, r -> addRun(m, r, nowMs, zone) }

    /** The run that [after] closed, or null when [before] to [after] closed none. */
    fun closedRun(before: DrainState, after: DrainState): DrainRun? =
        after.runs.lastOrNull()?.takeIf { it.endMs > (before.runs.lastOrNull()?.endMs ?: Long.MIN_VALUE) }

    /** How long a full charge lasted in month [m]; null with less than [MIN_LISTENED_MS] or [MIN_DROP_PCT] in it. */
    fun fullChargeMs(m: MonthDrain): Long? {
        if (m.durationMs < MIN_LISTENED_MS || m.dropPct < MIN_DROP_PCT) return null
        return lastsMs(100, Rate(m.dropPct * 3_600_000.0 / m.durationMs, m.durationMs))
    }

    /** The full charge per month: the last [SHOWN_MONTHS] months with enough data, oldest first. */
    fun health(months: Map<String, MonthDrain>): List<Pair<YearMonth, Long>> =
        months.entries.sortedBy { it.key }
            .mapNotNull { (k, m) -> fullChargeMs(m)?.let { YearMonth.parse(k) to it } }
            .takeLast(SHOWN_MONTHS)

    /** [latestMs] against [firstMs], in percent, rounded: -6 is 6% less. */
    fun changePct(firstMs: Long, latestMs: Long): Int = Math.round((latestMs - firstMs) * 100.0 / firstMs).toInt()

    /**
     * The level that runs out first: the lower bud of those out of the case and not charging. Null when no bud
     * is in use (both in the case or charging).
     */
    fun budsLevel(left: Int, right: Int, leftInUse: Boolean, rightInUse: Boolean): Int? =
        listOfNotNull(left.takeIf { it >= 0 && leftInUse }, right.takeIf { it >= 0 && rightInUse }).minOrNull()

    /** The buds [budsLevel] takes, as bits: 1 left, 2 right. 0 when none is in use. */
    fun budsInUse(left: Int, right: Int, leftInUse: Boolean, rightInUse: Boolean): Int =
        (if (left >= 0 && leftInUse) 1 else 0) or (if (right >= 0 && rightInUse) 2 else 0)

    /** A bud is in use when it does not charge and is not in the case: wear status 4, or 0 (docked and asleep). */
    fun inUse(wearStatus: Int, charging: Boolean): Boolean = !charging && wearStatus != 4 && wearStatus != 0

    // --- Storage: one JSON value per device in the app's preferences ---

    private const val KEY_PREFIX = "batteryDrain:"
    private val lock = Any()

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    fun load(c: Context, deviceId: String): DrainState = synchronized(lock) {
        val raw = prefs(c).getString(KEY_PREFIX + deviceId, null) ?: return DrainState()
        runCatching {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("runs")
            DrainState(
                runs = (0 until arr.length()).map { i ->
                    arr.getJSONObject(i).let { DrainRun(it.getLong("end"), it.getInt("drop"), it.getLong("ms")) }
                },
                lastLevel = o.optInt("last", -1),
                firstDropLevel = o.optInt("firstLevel", -1), firstDropMs = o.optLong("firstMs"),
                lastDropLevel = o.optInt("lastLevel", -1), lastDropMs = o.optLong("lastMs"),
                buds = o.optInt("buds")
            )
        }.onFailure { PacketLogger.error("battery drain read", it) }.getOrDefault(DrainState())
    }

    private fun save(c: Context, deviceId: String, s: DrainState) {
        val arr = JSONArray()
        s.runs.forEach { arr.put(JSONObject().put("end", it.endMs).put("drop", it.dropPct).put("ms", it.durationMs)) }
        val o = JSONObject().put("runs", arr).put("last", s.lastLevel)
            .put("firstLevel", s.firstDropLevel).put("firstMs", s.firstDropMs)
            .put("lastLevel", s.lastDropLevel).put("lastMs", s.lastDropMs).put("buds", s.buds)
        prefs(c).edit().putString(KEY_PREFIX + deviceId, o.toString()).apply()
    }

    /** One battery report: both levels, and whether each bud is in use ([inUse]). */
    fun record(c: Context, deviceId: String, left: Int, right: Int, leftInUse: Boolean, rightInUse: Boolean) {
        val level = budsLevel(left, right, leftInUse, rightInUse)
        record(c, deviceId, level ?: -1, draining = level != null, buds = budsInUse(left, right, leftInUse, rightInUse))
    }

    fun record(c: Context, deviceId: String, level: Int, draining: Boolean, buds: Int = 0) = synchronized(lock) {
        val before = load(c, deviceId)
        store(c, deviceId, before, onLevel(before, System.currentTimeMillis(), level, draining, buds))
    }

    fun disconnected(c: Context, deviceId: String) = synchronized(lock) {
        for (id in listOf(deviceId, budId(deviceId, true), budId(deviceId, false))) {
            val before = load(c, id)
            store(c, id, before, onDisconnected(before, System.currentTimeMillis()))
        }
    }

    /**
     * Saves [after]; a run it closed goes into its month too. Was: the log compared the number of runs, and missed
     * a run at [MAX_RUNS].
     */
    private fun store(c: Context, deviceId: String, before: DrainState, after: DrainState) {
        if (after != before) save(c, deviceId, after)
        val run = closedRun(before, after) ?: return
        PacketLogger.log("BATTERY DRAIN: $deviceId ${run.dropPct}% in ${run.durationMs / 60_000} min")
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        saveMonths(c, deviceId, addRun(loadMonths(c, deviceId, before.runs, now, zone), run, now, zone))
    }

    private const val MONTHS_PREFIX = "batteryMonths:"

    /**
     * The months of [deviceId]. Not stored yet: the months of [seedRuns], so the history shows at once. Was: no
     * months.
     */
    private fun loadMonths(c: Context, deviceId: String, seedRuns: List<DrainRun>, nowMs: Long, zone: ZoneId): Map<String, MonthDrain> {
        val raw = prefs(c).getString(MONTHS_PREFIX + deviceId, null)
            ?: return seed(seedRuns, nowMs, zone).also { saveMonths(c, deviceId, it) }
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { MonthDrain(it.getInt("drop"), it.getLong("ms")) } }
        }.onFailure { PacketLogger.error("battery months read", it) }.getOrDefault(emptyMap())
    }

    private fun saveMonths(c: Context, deviceId: String, months: Map<String, MonthDrain>) {
        val o = JSONObject()
        months.forEach { (k, m) -> o.put(k, JSONObject().put("drop", m.dropPct).put("ms", m.durationMs)) }
        prefs(c).edit().putString(MONTHS_PREFIX + deviceId, o.toString()).apply()
    }

    /** The months of [deviceId] (Battery life › Battery health). */
    fun months(c: Context, deviceId: String): Map<String, MonthDrain> = synchronized(lock) {
        loadMonths(c, deviceId, load(c, deviceId).runs, System.currentTimeMillis(), ZoneId.systemDefault())
    }

    /** One bud's own history (Battery life, left and right apart): the device's id with ":L" or ":R". */
    fun budId(deviceId: String, left: Boolean) = deviceId + if (left) ":L" else ":R"

    /** Each bud on its own, next to the pair's [record]. */
    fun recordEach(c: Context, deviceId: String, left: Int, right: Int, leftInUse: Boolean, rightInUse: Boolean) {
        record(c, budId(deviceId, true), left, draining = leftInUse && left >= 0)
        record(c, budId(deviceId, false), right, draining = rightInUse && right >= 0)
    }

    /** Measured for each bud apart: the screen then shows left, right and case. */
    fun hasBuds(c: Context, deviceId: String) = load(c, budId(deviceId, true)) != DrainState() || load(c, budId(deviceId, false)) != DrainState()

    fun rate(c: Context, deviceId: String): Rate? = rate(load(c, deviceId), System.currentTimeMillis())
}
