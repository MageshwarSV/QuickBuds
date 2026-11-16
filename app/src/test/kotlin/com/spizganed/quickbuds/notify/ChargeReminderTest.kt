package com.spizganed.quickbuds.notify

import com.spizganed.quickbuds.notify.ChargeReminder.Part
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ChargeReminderTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    @Test fun thePartsBelowTheLevel() {
        assertEquals(listOf(Part.LEFT to 45, Part.CASE to 30), ChargeReminder.low(45, 80, 30, 60))
        // Unknown levels (-1) do not count; at the level is not below it.
        assertEquals(emptyList<Pair<Part, Int>>(), ChargeReminder.low(-1, 60, -1, 60))
    }

    @Test fun theNextTime() {
        val evening = ZonedDateTime.of(2026, 11, 16, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        val today9pm = ZonedDateTime.of(2026, 11, 16, 21, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(today9pm, ChargeReminder.nextAt(evening, 21 * 60, zone))
        val late = ZonedDateTime.of(2026, 11, 16, 22, 0, 0, 0, zone).toInstant().toEpochMilli()
        val tomorrow9pm = ZonedDateTime.of(2026, 11, 17, 21, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(tomorrow9pm, ChargeReminder.nextAt(late, 21 * 60, zone))
        // Exactly at the time: the next day.
        assertEquals(tomorrow9pm, ChargeReminder.nextAt(today9pm, 21 * 60, zone))
    }
}
