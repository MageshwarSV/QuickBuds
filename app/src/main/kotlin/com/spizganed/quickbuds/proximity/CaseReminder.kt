package com.spizganed.quickbuds.proximity

/**
 * "Only one earbud is in the case": one bud went into the case and the other stayed out, not in an ear,
 * for [delayMs]. Decided from the buds' wear reports alone (PROTOCOL.md §8), so it needs no signal strength.
 * Pure logic, no Android: [CaseReminderMonitor] feeds it reports and runs [check] after the delay.
 *
 *  - The delay leaves time to put the second bud in; one notification per event, never repeated.
 *  - The other bud in an ear (listening with one bud) is not a reminder.
 *  - It clears when both are in the case, the outside bud goes into an ear, or both come out.
 *  - A bud in the case reports 4, or 0 ("off"): OnePlus Nord Buds 3r report 0 for a bud put in the case, and
 *    a closed lid switches the case bud off. So with the other bud out of the ear (1/5), 0 counts as in the
 *    case. Both 0 (the lid closing) is both in the case; -1 (not reported) decides nothing.
 */
class CaseReminder(private val delayMs: Long = DELAY_MS) {

    enum class Side { LEFT, RIGHT }

    sealed class Effect {
        object None : Effect()
        /** [outside] is the bud still out of the case. */
        data class Notify(val outside: Side) : Effect()
        object Clear : Effect()
    }

    /** The bud left out while a reminder is pending or shown; null otherwise. */
    var outside: Side? = null
        private set
    var notified = false
        private set
    private var since = 0L
    /** The last report showed one bud in, one out: [check] may fire. */
    private var mixedNow = false

    /** When [check] is due, or null when nothing is pending. */
    val dueAt: Long? get() = if (outside != null && !notified) since + delayMs else null

    /** A wear report with raw codes (3/7 in ear, 1/5 out, 4 in case, 0 off, -1 not reported). */
    fun onWear(left: Int, right: Int, nowMs: Long): Effect {
        val leftIn = left == 4 || left == 0
        val rightIn = right == 4 || right == 0
        val leftOut = left == 1 || left == 5
        val rightOut = right == 1 || right == 5
        val worn = left == 3 || left == 7 || right == 3 || right == 7

        val mixed = when {
            leftIn && rightOut -> Side.RIGHT
            rightIn && leftOut -> Side.LEFT
            else -> null
        }
        mixedNow = mixed != null
        if (mixed != null) {
            if (outside != mixed) {
                val clear = notified
                outside = mixed
                notified = false
                since = nowMs
                return if (clear) Effect.Clear else Effect.None
            }
            return Effect.None
        }
        // Resolved: both in the case, a bud in an ear, or both out.
        val resolved = worn || (leftIn && rightIn) || (leftOut && rightOut)
        if (resolved && outside != null) return reset()
        return Effect.None
    }

    /** Runs at [dueAt]: notifies once if one bud is still in the case and the other out. */
    fun check(nowMs: Long): Effect {
        val side = outside ?: return Effect.None
        if (notified || !mixedNow || nowMs - since < delayMs) return Effect.None
        notified = true
        return Effect.Notify(side)
    }

    /** The link dropped: no more reports come, so nothing pending can be confirmed. A shown reminder stays. */
    fun onDisconnected() {
        if (!notified) outside = null
        mixedNow = false
    }

    /** The reminder was turned off. */
    fun stop(): Effect = reset()

    private fun reset(): Effect {
        val clear = notified
        outside = null
        notified = false
        mixedNow = false
        return if (clear) Effect.Clear else Effect.None
    }

    companion object {
        /** Time to put the second bud in before the reminder. */
        const val DELAY_MS = 15_000L
    }
}
