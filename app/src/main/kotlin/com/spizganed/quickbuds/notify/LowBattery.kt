package com.spizganed.quickbuds.notify

/**
 * When to say "charge it": per part (left bud, right bud, case), from the levels the buds report. Pure logic,
 * no Android ([LowBatteryAlert] posts the card).
 *
 *  - One alert at [warnAt] % and under, one more at [criticalAt] % and under; nothing repeats in between.
 *  - Charging, or back above [rearmAbove] %, re-arms the part and takes it off the card. A reconnect at the same
 *    low level does not alert again: only a new step down does.
 *  - A level of 0 or under means "not known" (PROTOCOL.md §7) and changes nothing.
 */
class LowBattery(
    private val warnAt: Int = WARN_AT,
    private val criticalAt: Int = CRITICAL_AT,
    private val rearmAbove: Int = REARM_ABOVE
) {

    enum class Part { LEFT, RIGHT, CASE }

    /** What the card must do after a report. */
    enum class Change { NONE, UPDATE, ALERT }

    /** 0 nothing said, 1 warned, 2 critical said. */
    private val stage = HashMap<Part, Int>()
    /** The parts low now (not charging, at or under [warnAt]) and their levels: what the card lists. */
    val low = LinkedHashMap<Part, Int>()

    /** A report for [part]: [Change.ALERT] for a new step down, [Change.UPDATE] when the card's list changed. */
    fun onLevel(part: Part, level: Int, charging: Boolean): Change {
        if (level <= 0) return Change.NONE
        if (charging || level > rearmAbove) {
            stage[part] = 0
            return if (low.remove(part) != null) Change.UPDATE else Change.NONE
        }
        val target = when {
            level <= criticalAt -> 2
            level <= warnAt -> 1
            else -> 0
        }
        if (target == 0) {
            // Between the warning and the re-arm level: stays said, leaves the card.
            return if (low.remove(part) != null) Change.UPDATE else Change.NONE
        }
        val listed = low.put(part, level)
        if (target > (stage[part] ?: 0)) {
            stage[part] = target
            return Change.ALERT
        }
        return if (listed != level) Change.UPDATE else Change.NONE
    }

    /** Forget every part: the alerts were turned off, so nothing said before still holds. */
    fun reset() {
        stage.clear()
        low.clear()
    }

    companion object {
        const val WARN_AT = 20
        const val CRITICAL_AT = 10
        const val REARM_ABOVE = 30
    }
}
