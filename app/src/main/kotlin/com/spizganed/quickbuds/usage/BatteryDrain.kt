package com.spizganed.quickbuds.usage

import android.content.Context
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.ui.ThemeRes
import org.json.JSONArray
import org.json.JSONObject

/** One stretch of listening in which the level went down: from the first drop seen to the last one. */
data class DrainRun(val endMs: Long, val dropPct: Int, val durationMs: Long)

/** One device's drain history and the stretch now being measured. */
data class DrainState(
    val runs: List<DrainRun> = emptyList(),
    val lastLevel: Int = -1,
    val firstDropLevel: Int = -1,
    val firstDropMs: Long = 0L,
    val lastDropLevel: Int = -1,
    val lastDropMs: Long = 0L
)

/**
 * Battery life from history: how fast the buds' level falls while in use, so "how long will this charge last".
 *
 * Buds report the level in steps (1, 5 or 10%). A stretch is measured from the first drop seen to the last one,
 * both at the moment the new step was reported: 90% at 10:00, then 60% at 11:30 is 30% in 1 h 30 min. Starting at
 * the connect would count part of a step that began earlier. A stretch ends on charging, a bud into the case, a
 * level that went up, or a disconnect.
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

    /** A level report. [draining]: in use, out of the case and not charging. */
    fun onLevel(s: DrainState, nowMs: Long, level: Int, draining: Boolean): DrainState {
        if (!draining || level < 0) return close(s, nowMs)
        if (s.lastLevel < 0) return s.copy(lastLevel = level)
        if (level > s.lastLevel) return close(s, nowMs).copy(lastLevel = level)   // charged in between
        if (level == s.lastLevel) return s
        return if (s.firstDropLevel < 0) s.copy(lastLevel = level, firstDropLevel = level, firstDropMs = nowMs,
            lastDropLevel = level, lastDropMs = nowMs)
        else s.copy(lastLevel = level, lastDropLevel = level, lastDropMs = nowMs)
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

    /**
     * The level that runs out first: the lower bud of those out of the case and not charging. Null when no bud
     * is in use (both in the case or charging).
     */
    fun budsLevel(left: Int, right: Int, leftInUse: Boolean, rightInUse: Boolean): Int? =
        listOfNotNull(left.takeIf { it >= 0 && leftInUse }, right.takeIf { it >= 0 && rightInUse }).minOrNull()

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
                lastDropLevel = o.optInt("lastLevel", -1), lastDropMs = o.optLong("lastMs")
            )
        }.onFailure { PacketLogger.error("battery drain read", it) }.getOrDefault(DrainState())
    }

    private fun save(c: Context, deviceId: String, s: DrainState) {
        val arr = JSONArray()
        s.runs.forEach { arr.put(JSONObject().put("end", it.endMs).put("drop", it.dropPct).put("ms", it.durationMs)) }
        val o = JSONObject().put("runs", arr).put("last", s.lastLevel)
            .put("firstLevel", s.firstDropLevel).put("firstMs", s.firstDropMs)
            .put("lastLevel", s.lastDropLevel).put("lastMs", s.lastDropMs)
        prefs(c).edit().putString(KEY_PREFIX + deviceId, o.toString()).apply()
    }

    fun record(c: Context, deviceId: String, level: Int, draining: Boolean) = synchronized(lock) {
        val before = load(c, deviceId)
        val after = onLevel(before, System.currentTimeMillis(), level, draining)
        if (after != before) save(c, deviceId, after)
        if (after.runs.size > before.runs.size) after.runs.last().let {
            PacketLogger.log("BATTERY DRAIN: $deviceId ${it.dropPct}% in ${it.durationMs / 60_000} min")
        }
    }

    fun disconnected(c: Context, deviceId: String) = synchronized(lock) {
        val before = load(c, deviceId)
        val after = onDisconnected(before, System.currentTimeMillis())
        if (after != before) save(c, deviceId, after)
    }

    fun rate(c: Context, deviceId: String): Rate? = rate(load(c, deviceId), System.currentTimeMillis())
}
