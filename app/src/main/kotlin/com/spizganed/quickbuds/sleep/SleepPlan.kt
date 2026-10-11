package com.spizganed.quickbuds.sleep

import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * When the sleep timer goes off, from what the user set. Pure: [SleepTimer] schedules it, tests check it.
 *  - a timer: hours and minutes from now, up to [MAX_MINUTES];
 *  - a clock time (12 h with AM/PM, or 24 h): its next occurrence, tomorrow when it has passed today.
 */
object SleepPlan {

    /** A day: a longer timer is a mistake, not a plan. */
    const val MAX_MINUTES = 24 * 60

    /** The time [hours] and [minutes] from [nowMs]; null for zero, negative or more than a day. */
    fun afterDuration(nowMs: Long, hours: Int, minutes: Int): Long? {
        val total = hours * 60 + minutes
        if (hours < 0 || minutes < 0 || total <= 0 || total > MAX_MINUTES) return null
        return nowMs + total * 60_000L
    }

    /** The next [hour24]:[minute] after [now]: today if still ahead, else tomorrow. Daylight-saving changes included. */
    fun atClock(now: ZonedDateTime, hour24: Int, minute: Int): ZonedDateTime {
        val today = now.with(LocalTime.of(hour24, minute))
        return if (today.isAfter(now)) today else now.plusDays(1).with(LocalTime.of(hour24, minute))
    }

    /** 12 h to 24 h: 12 AM is 0, 12 PM is 12. */
    fun hour24(hour12: Int, pm: Boolean): Int = (hour12 % 12) + if (pm) 12 else 0
}
