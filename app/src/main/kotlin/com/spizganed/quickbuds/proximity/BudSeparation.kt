package com.spizganed.quickbuds.proximity

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.notify.BatteryCard
import com.spizganed.quickbuds.widget.WidgetStateStore

/**
 * One earbud left behind: it was out of the case and lost contact with the other one. The buds report each bud's
 * state (PROTOCOL.md §8): 0 is "disconnected". A bud put into the case often reports 0 at once too (docked and
 * asleep), without 4 first, so a 0 alone says nothing. A bud counts as left behind only when nothing says it went
 * into the case: the case did not open, the bud does not charge, and the case level was not reported since. Was:
 * the 0 alone, and a bud put into the case got the warning. Pure.
 */
object BudSeparation {
    enum class Side { LEFT, RIGHT }

    /** Out of ear (1, 5) or in ear (3, 7): out of the case. */
    private val OUT = setOf(1, 3, 5, 7)
    /** At this level or below, a bud that goes is most likely empty, not left behind. */
    const val EMPTY_PCT = 5
    /** The case may open a little before the bud goes into it. */
    const val LOOKBACK_MS = 10_000L

    /** [prev] and [now]: this bud's state before and now; [other]: the other bud's now; [level]: this bud's battery. */
    fun lost(prev: Int, now: Int, other: Int, level: Int): Boolean =
        prev in OUT && now == 0 && other > 0 && (level < 0 || level > EMPTY_PCT)

    /**
     * Whether the bud went into the case, not away: since [sinceMs] (when it reported 0), the case was open
     * ([caseOpenAtMs], the last time the buds reported it so; 0 never), the bud charges, or the case level came
     * ([caseLevelAtMs]). All times on one clock.
     */
    fun intoCase(sinceMs: Long, caseOpenAtMs: Long, charging: Boolean, caseLevelAtMs: Long): Boolean =
        charging || (caseOpenAtMs > 0 && caseOpenAtMs >= sinceMs - LOOKBACK_MS) || caseLevelAtMs >= sinceMs
}

/**
 * Watches the wear reports of one connection for [BudSeparation], and warns after [CONFIRM_MS] if the bud is still
 * gone and did not go into the case ([LossNotifier.budLost]). The card goes when the bud is back or in the case.
 * Only with Loss protection on. Main thread.
 */
class BudSeparationWatch(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    /** The states seen in this connection; -1 not seen yet. */
    private var left = -1
    private var right = -1
    private var pending: BudSeparation.Side? = null
    /** When the pending bud reported 0 (wall clock, as the stored case level time). */
    private var since = 0L
    private var warned: BudSeparation.Side? = null
    /** The last time the buds reported the case open (wall clock); 0 not in this connection. */
    private var caseOpenAt = 0L
    private val confirm = Runnable { confirmed() }

    private fun log(msg: String) = PacketLogger.log("BUD LEFT BEHIND: $msg")

    /**
     * A wear report: -1 for a part the report does not name. [caseSt]: the case's own state, above 0 while open.
     * [levelLeft]/[levelRight]: battery, -1 not known.
     */
    fun onWear(l: Int, r: Int, caseSt: Int, levelLeft: Int, levelRight: Int) {
        if (caseSt > 0) caseOpenAt = System.currentTimeMillis()
        val nl = if (l >= 0) l else left
        val nr = if (r >= 0) r else right
        val side = when {
            BudSeparation.lost(left, nl, nr, levelLeft) -> BudSeparation.Side.LEFT
            BudSeparation.lost(right, nr, nl, levelRight) -> BudSeparation.Side.RIGHT
            else -> null
        }
        left = nl
        right = nr
        // Back in contact: no warning, or the warning goes.
        if (pending != null && stateOf(pending!!) != 0) { handler.removeCallbacks(confirm); pending = null }
        if (warned != null && stateOf(warned!!) != 0) clear("back")
        if (side != null && warned == null && pending == null && ProximityMonitor.enabled(context)) {
            log("${name(side)} bud reports 0: warning in ${CONFIRM_MS / 1000} s unless it went into the case")
            pending = side
            since = System.currentTimeMillis()
            handler.postDelayed(confirm, CONFIRM_MS)
        }
        if (warned != null && inCase(warned!!, since)) clear("in the case")
    }

    /** A battery report: a bud that charges, or a case level, says the bud on the warning is in the case after all. */
    fun onBattery() {
        val w = warned ?: return
        if (inCase(w, since)) clear("in the case")
    }

    private fun name(side: BudSeparation.Side) = side.name.lowercase()

    private fun stateOf(side: BudSeparation.Side) = if (side == BudSeparation.Side.LEFT) left else right

    private fun inCase(side: BudSeparation.Side, sinceMs: Long) = BudSeparation.intoCase(sinceMs, caseOpenAt,
        if (side == BudSeparation.Side.LEFT) BatteryCard.leftCharging else BatteryCard.rightCharging,
        WidgetStateStore.read(context).caseBatteryAt)

    private fun clear(why: String) {
        log("${name(warned!!)} bud $why: warning off")
        LossNotifier.clearBudLost(context)
        warned = null
    }

    private fun confirmed() {
        val side = pending ?: return
        pending = null
        if (stateOf(side) != 0) return
        if (inCase(side, since)) return log("${name(side)} bud went into the case: no warning")
        warned = side
        log("${name(side)} bud out of the case and out of contact: warning")
        LossNotifier.budLost(context, side == BudSeparation.Side.LEFT)
    }

    /** The link came up or went down: a new connection starts from nothing. A warning on screen stays. */
    fun reset() {
        handler.removeCallbacks(confirm)
        pending = null
        left = -1
        right = -1
        caseOpenAt = 0L
    }

    companion object {
        /** Long enough for a bud in the case to start charging or report the case level. */
        const val CONFIRM_MS = 10_000L
    }
}
