package com.spizganed.quickbuds.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SleepPlanTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(h: Int, m: Int, day: Int = 10) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone)

    @Test fun aTimerIsNowPlusItsLength() =
        assertEquals(1_000L + 90 * 60_000L, SleepPlan.afterDuration(1_000L, 1, 30))

    @Test fun aZeroTimerIsNothing() {
        assertNull(SleepPlan.afterDuration(0, 0, 0))
        assertNull(SleepPlan.afterDuration(0, -1, 30))
    }

    @Test fun moreThanADayIsNothing() {
        assertNull(SleepPlan.afterDuration(0, 24, 1))
        assertEquals(24 * 60 * 60_000L, SleepPlan.afterDuration(0, 24, 0))
    }

    @Test fun aClockTimeLaterTodayIsToday() = assertEquals(at(23, 30), SleepPlan.atClock(at(22, 15), 23, 30))

    @Test fun aClockTimeAlreadyPassedIsTomorrow() = assertEquals(at(7, 0, day = 11), SleepPlan.atClock(at(22, 15), 7, 0))

    @Test fun exactlyNowIsTomorrow() = assertEquals(at(22, 15, day = 11), SleepPlan.atClock(at(22, 15), 22, 15))

    @Test fun twelveHourClockToTwentyFour() {
        assertEquals(0, SleepPlan.hour24(12, pm = false))      // 12 AM
        assertEquals(12, SleepPlan.hour24(12, pm = true))      // 12 PM
        assertEquals(23, SleepPlan.hour24(11, pm = true))
        assertEquals(7, SleepPlan.hour24(7, pm = false))
    }
}
