package com.spizganed.quickbuds.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

// 17
class UsageRetentionTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val buds = UsageDevice("AA:AA:AA:AA:AA:01", "Buds", null)
    private val clock = FakeClock.at(zone, "2024-01-01T00:00")
    private val store = InMemoryUsageStore()
    private val tracker = UsageTracker(store, clock)

    /** Real time passes until [dateTime]: both clocks move. */
    private fun until(dateTime: String) = clock.advance(ms(zone, dateTime) - clock.wall)

    /** One session from [from] lasting [minutes]. */
    private fun session(from: String, minutes: Long) {
        until(from)
        tracker.onConnected(buds, newLink = true)
        clock.advance(minutes * MIN)
        tracker.onDisconnected(buds.id)
    }

    private fun d(s: String) = LocalDate.parse(s)

    @Test fun keepsTheLast31DaysAndRollsUpTheRest() {
        session("2026-10-01T10:00", 60)               // older than the period
        session("2026-10-10T23:00", 120)              // crosses into 11 Oct, the first kept day
        session("2026-10-11T09:00", 30)
        session("2026-11-10T07:00", 45)               // today
        until("2026-11-10T12:00")
        val before = store.days(tracker, zone)

        val rolled = UsageRetention.apply(store, clock.wall, zone, 31)

        assertEquals(1, rolled)
        assertEquals(d("2026-10-11"), UsageRetention.firstKeptDay(d("2026-11-10"), 31))
        // The session crossing into the kept period stays in detail, and today's too.
        assertEquals(3, store.sessions().size)
        assertTrue(store.sessions().none { it.startMs == ms(zone, "2026-10-01T10:00") })
        assertNotNull(store.summaries().single { it.date == d("2026-10-01") })
        // Every day, week and month total is the same as before.
        assertEquals(before, store.days(tracker, zone))
        assertEquals(
            UsageStats.month(before, YearMonth.of(2026, 10), d("2026-11-10")),
            UsageStats.month(store.days(tracker, zone), YearMonth.of(2026, 10), d("2026-11-10"))
        )
    }

    @Test fun shortRetentionKeepsTheCurrentMonthTotal() {
        session("2026-10-02T10:00", 90)
        session("2026-10-05T10:00", 30)
        session("2026-10-20T10:00", 60)
        until("2026-10-21T09:00")
        val monthBefore = UsageStats.month(store.days(tracker, zone), YearMonth.of(2026, 10), d("2026-10-21"))

        UsageRetention.apply(store, clock.wall, zone, 7)

        assertEquals(1, store.sessions().size)
        val monthAfter = UsageStats.month(store.days(tracker, zone), YearMonth.of(2026, 10), d("2026-10-21"))
        assertEquals(monthBefore, monthAfter)
        assertEquals(3 * HOUR, monthAfter.totalMs)
        assertEquals(3, monthAfter.sessions)
    }

    @Test fun neverTouchesAnOpenSession() {
        until("2026-08-01T10:00")
        tracker.onConnected(buds, newLink = true)
        clock.advance(80L * 24 * HOUR)
        UsageRetention.apply(store, clock.wall, zone, 7)
        assertNotNull(store.openSession(buds.id))
    }

    @Test fun keepsTodayWithTheShortestPeriod() {
        session("2026-10-21T00:05", 10)
        until("2026-10-21T23:59")
        UsageRetention.apply(store, clock.wall, zone, 1)
        assertEquals(1, store.sessions().size)
    }

    @Test fun rollingUpTwiceMergesIntoOneDay() {
        session("2026-09-01T10:00", 30)
        session("2026-09-01T23:50", 30)               // 10 min on 1 Sep, 20 min on 2 Sep
        until("2026-10-02T09:00")                     // first kept day: 2 Sep
        UsageRetention.apply(store, clock.wall, zone, 31)
        assertEquals(1, store.sessions().size)
        until("2026-10-03T09:00")                     // first kept day: 3 Sep
        UsageRetention.apply(store, clock.wall, zone, 31)
        assertTrue(store.sessions().isEmpty())
        val (first, second) = store.summaries()
        assertEquals(DaySummary(d("2026-09-01"), buds.id, 40 * MIN, 2, 2, 30 * MIN), first)
        assertEquals(DaySummary(d("2026-09-02"), buds.id, 20 * MIN, 1, 0, 20 * MIN), second)
    }

    @Test fun deletesSummariesAfterTwoYears() {
        session("2024-01-10T10:00", 30)
        session("2026-01-10T10:00", 30)
        until("2026-10-21T09:00")
        UsageRetention.apply(store, clock.wall, zone, 31)
        assertEquals(listOf(d("2026-01-10")), store.summaries().map { it.date })
    }
}
