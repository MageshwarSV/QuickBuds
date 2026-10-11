package com.spizganed.quickbuds.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class UsageStatsTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private var nextId = 1L

    private fun span(from: String, to: String, device: String = "A") =
        UsageSpan(nextId++, device, ms(zone, from), ms(zone, to), open = false)

    private fun days(vararg spans: UsageSpan, device: String? = null) = UsageStats.byDay(spans.toList(), emptyList(), zone, device)

    private fun d(s: String) = LocalDate.parse(s)

    // 10
    @Test fun midnightSplitsAcrossTwoDays() {
        val days = days(span("2026-10-08T23:50", "2026-10-09T00:30"))
        assertEquals(10 * MIN, days[d("2026-10-08")]!!.totalMs)
        assertEquals(30 * MIN, days[d("2026-10-09")]!!.totalMs)
        // One session, counted on both days it touches, started on the first.
        assertEquals(1, days[d("2026-10-08")]!!.started)
        assertEquals(0, days[d("2026-10-09")]!!.started)
        assertEquals(1, days[d("2026-10-09")]!!.sessions)
    }

    // 11
    @Test fun weekBoundarySplitsBetweenWeeks() {
        // Sunday 11 Oct 23:30 to Monday 12 Oct 00:45.
        val days = days(span("2026-10-11T23:30", "2026-10-12T00:45"))
        val today = d("2026-10-20")
        val first = UsageStats.week(days, d("2026-10-11"), today)
        val second = UsageStats.week(days, d("2026-10-12"), today)
        assertEquals(d("2026-10-05"), first.from)
        assertEquals(d("2026-10-12"), second.from)
        assertEquals(30 * MIN, first.totalMs)
        assertEquals(45 * MIN, second.totalMs)
        assertEquals(1, first.sessions)
        assertEquals(1, second.sessions)
    }

    // 12
    @Test fun monthBoundarySplitsBetweenMonths() {
        val days = days(span("2026-10-31T23:00", "2026-11-01T01:00"))
        val today = d("2026-11-15")
        assertEquals(HOUR, UsageStats.month(days, YearMonth.of(2026, 10), today).totalMs)
        assertEquals(HOUR, UsageStats.month(days, YearMonth.of(2026, 11), today).totalMs)
    }

    @Test fun yearBoundarySplitsBetweenYears() {
        val days = days(span("2026-12-31T23:30", "2027-01-01T00:15"))
        assertEquals(30 * MIN, days[d("2026-12-31")]!!.totalMs)
        assertEquals(15 * MIN, days[d("2027-01-01")]!!.totalMs)
        assertEquals(15 * MIN, UsageStats.month(days, YearMonth.of(2027, 1), d("2027-01-10")).totalMs)
    }

    @Test fun daylightSavingDayHas25Hours() {
        // Europe/Berlin falls back on 25 Oct 2026: that day is 25 h long.
        val parts = UsageStats.split(ms(zone, "2026-10-25T00:00"), ms(zone, "2026-10-26T00:00"), zone)
        assertEquals(listOf(d("2026-10-25") to 25 * HOUR), parts)
    }

    @Test fun zeroLengthSessionAddsNothing() {
        assertTrue(UsageStats.split(1000, 1000, zone).isEmpty())
    }

    // 14
    @Test fun dailyAggregation() {
        val days = days(
            span("2026-10-08T09:15", "2026-10-08T10:05"),
            span("2026-10-08T13:20", "2026-10-08T15:10"),
            span("2026-10-08T18:30", "2026-10-08T19:32"),
            span("2026-10-08T22:00", "2026-10-08T22:00:30")
        )
        val day = days[d("2026-10-08")]!!
        assertEquals(50 * MIN + 110 * MIN + 62 * MIN + 30_000, day.totalMs)
        assertEquals(4, day.sessions)
        assertEquals(110 * MIN, day.longestMs)
        assertEquals(day.totalMs / 4, day.averageSessionMs)
    }

    @Test fun sessionListOfADayClipsToTheDay() {
        val spans = listOf(span("2026-10-08T23:50", "2026-10-09T00:30"), span("2026-10-09T09:00", "2026-10-09T10:00"))
        val list = UsageStats.sessionsOn(spans, d("2026-10-09"), zone)
        assertEquals(listOf(30 * MIN, HOUR), list.map { it.second })
    }

    // 15
    @Test fun weeklyAggregation() {
        // Week of Monday 5 Oct 2026: Mon 2h, Wed 4h10m (two sessions), Sun 30m.
        val days = days(
            span("2026-10-05T10:00", "2026-10-05T12:00"),
            span("2026-10-07T08:00", "2026-10-07T10:10"),
            span("2026-10-07T18:00", "2026-10-07T20:00"),
            span("2026-10-11T20:00", "2026-10-11T20:30")
        )
        val p = UsageStats.week(days, d("2026-10-08"), today = d("2026-10-20"))
        assertEquals(d("2026-10-05"), p.from)
        assertEquals(d("2026-10-11"), p.to)
        assertEquals(6 * HOUR + 40 * MIN, p.totalMs)
        assertEquals(3, p.activeDays)
        assertEquals(4, p.sessions)
        assertEquals(d("2026-10-07"), p.longestDay!!.date)
        assertEquals(4 * HOUR + 10 * MIN, p.longestDay!!.totalMs)
        assertEquals(p.totalMs / 7, p.averagePerDayMs)
        assertEquals(p.totalMs / 3, p.averagePerActiveDayMs)
        assertEquals(DayOfWeek.MONDAY, UsageStats.weekBars(days, d("2026-10-08")).first().date.dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, UsageStats.weekBars(days, d("2026-10-08")).last().date.dayOfWeek)
    }

    @Test fun currentWeekAveragesOverTheDaysSoFar() {
        val days = days(span("2026-10-05T10:00", "2026-10-05T13:00"))
        // Today is Wednesday: three days so far.
        val p = UsageStats.week(days, d("2026-10-07"), today = d("2026-10-07"))
        assertEquals(3, p.averageDays)
        assertEquals(HOUR, p.averagePerDayMs)
    }

    @Test fun periodCountsASessionRunningInFromBeforeOnce() {
        val days = days(span("2026-10-04T23:00", "2026-10-05T02:00"), span("2026-10-05T10:00", "2026-10-05T11:00"))
        val p = UsageStats.week(days, d("2026-10-05"), today = d("2026-10-20"))
        assertEquals(2, p.sessions)
        assertEquals(3 * HOUR, p.totalMs)
    }

    // 16
    @Test fun monthlyAggregation() {
        val days = days(
            span("2026-10-01T10:00", "2026-10-01T12:00"),
            span("2026-10-08T09:00", "2026-10-08T13:42"),
            span("2026-10-31T22:00", "2026-10-31T23:00")
        )
        val p = UsageStats.month(days, YearMonth.of(2026, 10), today = d("2026-11-05"))
        assertEquals(7 * HOUR + 42 * MIN, p.totalMs)
        assertEquals(31, p.averageDays)
        assertEquals(p.totalMs / 31, p.averagePerDayMs)
        assertEquals(3, p.activeDays)
        assertEquals(p.totalMs / 3, p.averagePerActiveDayMs)
        assertEquals(3, p.sessions)
        assertEquals(d("2026-10-08"), p.longestDay!!.date)
    }

    @Test fun summariesAndDetailAddUpOnTheSameDay() {
        val rolled = DaySummary(d("2026-10-08"), "A", HOUR, 2, 2, 40 * MIN)
        val live = span("2026-10-08T23:00", "2026-10-09T01:00")
        val days = UsageStats.byDay(listOf(live), listOf(rolled), zone)
        val day = days[d("2026-10-08")]!!
        assertEquals(2 * HOUR, day.totalMs)
        assertEquals(3, day.sessions)
        assertEquals(60 * MIN, day.longestMs)
    }

    // 18
    @Test fun emptyDataHasNoFirstDayAndOnlyZeroBars() {
        val days = days()
        assertNull(UsageStats.firstDay(days))
        val bars = UsageStats.lastDays(days, d("2026-10-08"))
        assertEquals(7, bars.size)
        assertTrue(bars.all { it.totalMs == 0L && it.sessions == 0 })
    }

    // 19 + 20
    @Test fun sevenDayChartKeepsZeroDays() {
        val days = days(span("2026-10-04T10:00", "2026-10-04T12:10"), span("2026-10-08T10:00", "2026-10-08T12:30"))
        val bars = UsageStats.lastDays(days, d("2026-10-08"))
        assertEquals((2..8).map { d("2026-10-0$it") }, bars.map { it.date })
        assertEquals(listOf(0L, 0L, 130 * MIN, 0L, 0L, 0L, 150 * MIN), bars.map { it.totalMs })
    }

    // 21 + 25
    @Test fun thirtyOneDayMonthChart() {
        val days = days(span("2026-10-31T10:00", "2026-10-31T11:00"))
        val bars = UsageStats.monthBars(days, YearMonth.of(2026, 10))
        assertEquals(31, bars.size)
        assertEquals(1, bars.first().date.dayOfMonth)
        assertEquals(HOUR, bars.last().totalMs)
    }

    // 22
    @Test fun twentyEightDayMonth() = assertEquals(28, UsageStats.monthBars(emptyMap(), YearMonth.of(2026, 2)).size)

    // 23
    @Test fun twentyNineDayMonth() = assertEquals(29, UsageStats.monthBars(emptyMap(), YearMonth.of(2028, 2)).size)

    // 24
    @Test fun thirtyDayMonth() = assertEquals(30, UsageStats.monthBars(emptyMap(), YearMonth.of(2026, 4)).size)

    // 26
    @Test fun deviceFilterRecomputesEverything() {
        val spans = arrayOf(
            span("2026-10-08T08:00", "2026-10-08T10:00", "A"),
            span("2026-10-08T14:00", "2026-10-08T15:30", "B"),
            span("2026-10-09T14:00", "2026-10-09T15:00", "B")
        )
        val all = days(*spans)
        val a = days(*spans, device = "A")
        val b = days(*spans, device = "B")
        val today = d("2026-10-09")
        val m = YearMonth.of(2026, 10)
        assertEquals(4 * HOUR + 30 * MIN, UsageStats.month(all, m, today).totalMs)
        assertEquals(2 * HOUR, UsageStats.month(a, m, today).totalMs)
        assertEquals(2 * HOUR + 30 * MIN, UsageStats.month(b, m, today).totalMs)
        assertEquals(listOf(2 * HOUR, 0L), UsageStats.lastDays(a, today, 2).map { it.totalMs })
        assertEquals(listOf(90 * MIN, HOUR), UsageStats.lastDays(b, today, 2).map { it.totalMs })
    }
}
