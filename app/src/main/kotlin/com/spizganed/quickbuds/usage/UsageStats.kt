package com.spizganed.quickbuds.usage

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Statistics from sessions and day summaries. Pure functions: no Android, no clock of their own.
 *
 * A session counts on every local day it touches, with only the part inside that day (23:50-00:30 is
 * 10 min on one day and 30 min on the next). Days come from the zone's own midnights, so a 23 h or 25 h
 * day (daylight saving) splits correctly, and so do week, month and year ends, which are day ends too.
 */
object UsageStats {

    /** The parts of [startMs]..[endMs] on each local day, in order. Empty for a zero-length span. */
    fun split(startMs: Long, endMs: Long, zone: ZoneId): List<Pair<LocalDate, Long>> {
        if (endMs <= startMs) return emptyList()
        val out = ArrayList<Pair<LocalDate, Long>>(2)
        var day = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        var cursor = startMs
        while (cursor < endMs) {
            val next = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val partEnd = minOf(endMs, next)
            if (partEnd > cursor) out += day to (partEnd - cursor)
            cursor = partEnd
            day = day.plusDays(1)
        }
        return out
    }

    /**
     * Usage per day: [spans] (sessions held in detail) plus [summaries] (rolled-up days), for one device
     * or all ([deviceId] null). A day can have both: a rolled-up session and a kept one that crosses into
     * it. Each session lands on each day once, so the two simply add up.
     */
    fun byDay(spans: List<UsageSpan>, summaries: List<DaySummary>, zone: ZoneId, deviceId: String? = null): Map<LocalDate, DayUsage> {
        val total = HashMap<LocalDate, Long>()
        val sessions = HashMap<LocalDate, Int>()
        val started = HashMap<LocalDate, Int>()
        val longest = HashMap<LocalDate, Long>()
        for (s in spans) {
            if (deviceId != null && s.deviceId != deviceId) continue
            val parts = split(s.startMs, s.endMs, zone)
            parts.forEachIndexed { i, (day, ms) ->
                total[day] = (total[day] ?: 0) + ms
                sessions[day] = (sessions[day] ?: 0) + 1
                if (i == 0) started[day] = (started[day] ?: 0) + 1
                longest[day] = maxOf(longest[day] ?: 0, ms)
            }
        }
        for (d in summaries) {
            if (deviceId != null && d.deviceId != deviceId) continue
            total[d.date] = (total[d.date] ?: 0) + d.totalMs
            sessions[d.date] = (sessions[d.date] ?: 0) + d.sessions
            started[d.date] = (started[d.date] ?: 0) + d.started
            longest[d.date] = maxOf(longest[d.date] ?: 0, d.longestMs)
        }
        return total.keys.associateWith { day ->
            DayUsage(day, total[day] ?: 0, sessions[day] ?: 0, started[day] ?: 0, longest[day] ?: 0)
        }
    }

    /** Rolls [sessions] (all closed) into one summary per day and device. */
    fun summarize(sessions: List<UsageSession>, zone: ZoneId): List<DaySummary> {
        val spans = sessions.mapNotNull { s -> s.endMs?.let { UsageSpan(s.id, s.deviceId, s.startMs, it, false) } }
        return spans.map { it.deviceId }.distinct().flatMap { device ->
            byDay(spans, emptyList(), zone, device).values.map {
                DaySummary(it.date, device, it.totalMs, it.sessions, it.started, it.longestMs)
            }
        }.sortedWith(compareBy({ it.date }, { it.deviceId }))
    }

    /** Totals over a range of days. [averageDays] is the divisor for the per-day average. */
    data class Period(
        val from: LocalDate,
        val to: LocalDate,
        val totalMs: Long,
        val sessions: Int,
        val activeDays: Int,
        val averageDays: Int,
        val longestDay: DayUsage?
    ) {
        val averagePerDayMs: Long get() = if (averageDays <= 0) 0 else totalMs / averageDays
        val averagePerActiveDayMs: Long get() = if (activeDays <= 0) 0 else totalMs / activeDays
    }

    /**
     * Totals for [from]..[to] (inclusive). The per-day average divides by the calendar days so far: all of
     * them for a past range, up to [today] for the current one, none for a future one.
     *
     * Sessions are counted once each, even across midnight: the sessions that started inside the range,
     * plus those still running from before it on its first day.
     */
    fun period(days: Map<LocalDate, DayUsage>, from: LocalDate, to: LocalDate, today: LocalDate): Period {
        var total = 0L
        var started = 0
        var active = 0
        var longest: DayUsage? = null
        var d = from
        while (!d.isAfter(to)) {
            val u = days[d]
            if (u != null && u.totalMs > 0) {
                total += u.totalMs
                active++
                if (longest == null || u.totalMs > longest.totalMs) longest = u
            }
            started += u?.started ?: 0
            d = d.plusDays(1)
        }
        val carried = days[from]?.let { it.sessions - it.started } ?: 0
        val lastCounted = if (today.isBefore(to)) today else to
        val averageDays = if (lastCounted.isBefore(from)) 0 else (lastCounted.toEpochDay() - from.toEpochDay() + 1).toInt()
        return Period(from, to, total, started + carried, active, averageDays, longest)
    }

    /** Monday of [date]'s week: weeks run Monday to Sunday. */
    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun week(days: Map<LocalDate, DayUsage>, date: LocalDate, today: LocalDate): Period {
        val from = weekStart(date)
        return period(days, from, from.plusDays(6), today)
    }

    fun month(days: Map<LocalDate, DayUsage>, month: YearMonth, today: LocalDate): Period =
        period(days, month.atDay(1), month.atEndOfMonth(), today)

    /** One bar per day, [from]..[to] inclusive; a day without usage is a zero bar, never missing. */
    fun bars(days: Map<LocalDate, DayUsage>, from: LocalDate, to: LocalDate): List<DayUsage> {
        val out = ArrayList<DayUsage>()
        var d = from
        while (!d.isAfter(to)) {
            out += days[d] ?: DayUsage.empty(d)
            d = d.plusDays(1)
        }
        return out
    }

    /** The [count] days ending on [end]: the daily chart (7 by default). */
    fun lastDays(days: Map<LocalDate, DayUsage>, end: LocalDate, count: Int = 7): List<DayUsage> =
        bars(days, end.minusDays((count - 1).toLong()), end)

    /** Monday to Sunday of [date]'s week. */
    fun weekBars(days: Map<LocalDate, DayUsage>, date: LocalDate): List<DayUsage> =
        weekStart(date).let { bars(days, it, it.plusDays(6)) }

    /** Day 1 to the month's last day: 28, 29, 30 or 31 bars. */
    fun monthBars(days: Map<LocalDate, DayUsage>, month: YearMonth): List<DayUsage> =
        bars(days, month.atDay(1), month.atEndOfMonth())

    /** The first day with any usage, or null when there is none. */
    fun firstDay(days: Map<LocalDate, DayUsage>): LocalDate? = days.values.filter { it.totalMs > 0 }.minOfOrNull { it.date }

    /** The sessions that touch [date], each with its part inside that day, oldest first. */
    fun sessionsOn(spans: List<UsageSpan>, date: LocalDate, zone: ZoneId, deviceId: String? = null): List<Pair<UsageSpan, Long>> {
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return spans.filter { (deviceId == null || it.deviceId == deviceId) && it.startMs < to && it.endMs > from }
            .sortedBy { it.startMs }
            .map { it to (minOf(it.endMs, to) - maxOf(it.startMs, from)) }
            .filter { it.second > 0 }
    }
}
