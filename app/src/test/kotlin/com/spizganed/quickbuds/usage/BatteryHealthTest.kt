package com.spizganed.quickbuds.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class BatteryHealthTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, m: Int, d: Int) = ZonedDateTime.of(y, m, d, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val h = 3_600_000L

    @Test fun aRunGoesIntoItsMonthAndMonthsAddUp() {
        val now = at(2026, 11, 16)
        var m = BatteryDrain.addRun(emptyMap(), DrainRun(at(2026, 10, 3), 20, 2 * h), now, zone)
        m = BatteryDrain.addRun(m, DrainRun(at(2026, 10, 20), 10, h), now, zone)
        m = BatteryDrain.addRun(m, DrainRun(at(2026, 11, 2), 7, h), now, zone)
        assertEquals(MonthDrain(30, 3 * h), m["2026-10"])
        assertEquals(MonthDrain(7, h), m["2026-11"])
    }

    @Test fun theFirstSummaryComesFromTheRunsKept() {
        val now = at(2026, 11, 16)
        val runs = listOf(DrainRun(at(2026, 10, 3), 20, 2 * h), DrainRun(at(2026, 11, 2), 7, h))
        assertEquals(setOf("2026-10", "2026-11"), BatteryDrain.seed(runs, now, zone).keys)
    }

    @Test fun monthsOlderThanTwoYearsGo() {
        val now = at(2026, 11, 16)
        val m = BatteryDrain.addRun(mapOf("2024-11" to MonthDrain(10, h), "2024-12" to MonthDrain(10, h)),
            DrainRun(at(2026, 11, 2), 7, h), now, zone)
        assertEquals(setOf("2024-12", "2026-11"), m.keys)
    }

    @Test fun aFullChargePerMonth() {
        // 30% in 3 h: 10% per hour, so a full charge is 10 h.
        assertEquals(10 * h, BatteryDrain.fullChargeMs(MonthDrain(30, 3 * h)))
        // Too little listening: no figure.
        assertNull(BatteryDrain.fullChargeMs(MonthDrain(30, 10 * 60_000L)))
        val health = BatteryDrain.health(mapOf("2026-11" to MonthDrain(30, 3 * h), "2026-10" to MonthDrain(25, 3 * h),
            "2026-09" to MonthDrain(2, 60_000L)))
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 11)), health.map { it.first })
        assertEquals(-17, BatteryDrain.changePct(12 * h, 10 * h))
    }
}
