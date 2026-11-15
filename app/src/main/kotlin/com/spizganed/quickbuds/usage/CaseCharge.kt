package com.spizganed.quickbuds.usage

import android.content.Context
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.ui.ThemeRes
import org.json.JSONArray
import org.json.JSONObject

/** A battery report with the case level known: the levels (-1 not reported), which buds sat in the case, the case charging. */
data class CaseSnap(
    val left: Int, val right: Int, val case: Int,
    val leftDocked: Boolean, val rightDocked: Boolean, val caseCharging: Boolean, val atMs: Long
)

/** Between two reports: the case lost [caseDrop]% while the buds in it gained [budGain]% (both buds added up). */
data class CaseStep(val atMs: Long, val caseDrop: Int, val budGain: Int)

data class CaseState(val snap: CaseSnap? = null, val steps: List<CaseStep> = emptyList())

/**
 * How much the case holds, from history: how much case level a charge of the buds takes. A bud in the case at one
 * report and higher at the next charged from the case in between (a bud gains only in the case). The lid closes
 * between the two often: the buds then report again when it opens. Steps count only while the case itself does
 * not charge, and only with a bud in the case.
 */
object CaseCharge {
    const val MAX_STEPS = 200
    /** Only steps of the last 60 days count. */
    const val WINDOW_MS = 60L * 24 * 60 * 60_000L
    /** Two reports further apart than this are not compared: too much is not known in between. */
    const val MAX_GAP_MS = 7L * 24 * 60 * 60_000L
    /** An estimate needs this much bud charging and this much case drop. */
    const val MIN_GAIN_PCT = 60
    const val MIN_DROP_PCT = 5

    fun onReport(s: CaseState, now: CaseSnap): CaseState {
        if (now.case < 0) return s
        val prev = s.snap
        val next = s.copy(snap = now)
        if (prev == null || prev.case < 0 || now.atMs - prev.atMs > MAX_GAP_MS || now.atMs < prev.atMs) return next
        // The case charged (or was plugged in) in between: its drop is not known.
        if (prev.caseCharging || now.caseCharging || now.case > prev.case) return next
        if (!prev.leftDocked && !prev.rightDocked) return next
        val gain = gain(prev.left, now.left, prev.leftDocked) + gain(prev.right, now.right, prev.rightDocked)
        val drop = prev.case - now.case
        if (gain == 0 && drop == 0) return next
        val steps = (s.steps + CaseStep(now.atMs, drop, gain)).filter { now.atMs - it.atMs <= WINDOW_MS }.takeLast(MAX_STEPS)
        return next.copy(steps = steps)
    }

    private fun gain(before: Int, after: Int, docked: Boolean) = if (docked && before >= 0 && after > before) after - before else 0

    /** Case percent used per bud percent charged, and how much bud charging it rests on. */
    data class Estimate(val casePerBudPct: Double, val gainPct: Int)

    fun estimate(s: CaseState, nowMs: Long): Estimate? {
        val steps = s.steps.filter { nowMs - it.atMs <= WINDOW_MS }
        val gain = steps.sumOf { it.budGain }
        val drop = steps.sumOf { it.caseDrop }
        if (gain < MIN_GAIN_PCT || drop < MIN_DROP_PCT) return null
        return Estimate(drop.toDouble() / gain, gain)
    }

    /** Full charges of both buds (0 to 100% each) that [caseLevel]% of the case gives. */
    fun charges(e: Estimate, caseLevel: Int): Double = caseLevel / (e.casePerBudPct * 200.0)

    /** The percent each bud gets from [caseLevel]% of the case, shared by both buds. */
    fun budPctFrom(e: Estimate, caseLevel: Int): Double = caseLevel / e.casePerBudPct / 2.0

    // --- Storage: one JSON value per device in the app's preferences ---

    private const val KEY_PREFIX = "caseCharge:"
    private val lock = Any()

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    fun load(c: Context, deviceId: String): CaseState = synchronized(lock) {
        val raw = prefs(c).getString(KEY_PREFIX + deviceId, null) ?: return CaseState()
        runCatching {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("steps")
            CaseState(
                snap = o.optJSONObject("snap")?.let {
                    CaseSnap(it.getInt("l"), it.getInt("r"), it.getInt("c"), it.getBoolean("ld"), it.getBoolean("rd"),
                        it.getBoolean("cc"), it.getLong("at"))
                },
                steps = (0 until arr.length()).map { i -> arr.getJSONObject(i).let { CaseStep(it.getLong("at"), it.getInt("d"), it.getInt("g")) } }
            )
        }.onFailure { PacketLogger.error("case charge read", it) }.getOrDefault(CaseState())
    }

    private fun save(c: Context, deviceId: String, s: CaseState) {
        val arr = JSONArray()
        s.steps.forEach { arr.put(JSONObject().put("at", it.atMs).put("d", it.caseDrop).put("g", it.budGain)) }
        val o = JSONObject().put("steps", arr)
        s.snap?.let {
            o.put("snap", JSONObject().put("l", it.left).put("r", it.right).put("c", it.case).put("ld", it.leftDocked)
                .put("rd", it.rightDocked).put("cc", it.caseCharging).put("at", it.atMs))
        }
        prefs(c).edit().putString(KEY_PREFIX + deviceId, o.toString()).apply()
    }

    fun record(c: Context, deviceId: String, snap: CaseSnap) = synchronized(lock) {
        val before = load(c, deviceId)
        val after = onReport(before, snap)
        if (after != before) save(c, deviceId, after)
        if (after.steps != before.steps) after.steps.lastOrNull()?.let {
            PacketLogger.log("CASE CHARGE: $deviceId case -${it.caseDrop}%, buds +${it.budGain}%")
        }
    }

    fun estimate(c: Context, deviceId: String): Estimate? = estimate(load(c, deviceId), System.currentTimeMillis())
}
