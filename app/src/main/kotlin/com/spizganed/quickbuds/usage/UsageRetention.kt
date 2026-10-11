package com.spizganed.quickbuds.usage

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Keeps the database small without making old reports wrong.
 *
 *  - Sessions (the detail: start and end times) are kept for [days] days, today included. With 31 days
 *    on 10 November, 11 October to 10 November stay.
 *  - An older session is not just deleted: in the same transaction it is rolled up into one
 *    [DaySummary] per day and device (total, session count, longest stretch). Day, week and month
 *    totals stay exact; only the session list of those days is gone.
 *  - Day summaries are kept for [SUMMARY_DAYS] days, then deleted. The database holds at most
 *    [days] days of sessions and [SUMMARY_DAYS] rows per device.
 *
 * An open session is never touched, and neither is any session that ends inside the kept days, so
 * today and the current month keep their detail.
 */
object UsageRetention {

    /** Settings › History retention. */
    val CHOICES = listOf(7, 14, 31, 90)
    const val DEFAULT_DAYS = 31
    /** Two years of day totals, for the month and week charts of past periods. */
    const val SUMMARY_DAYS = 731L

    /** The first day whose sessions are kept. */
    fun firstKeptDay(today: LocalDate, days: Int): LocalDate = today.minusDays((days.coerceAtLeast(1) - 1).toLong())

    /** Returns the number of sessions rolled up. */
    fun apply(store: UsageStore, nowMs: Long, zone: ZoneId, days: Int): Int {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val cutoffMs = firstKeptDay(today, days).atStartOfDay(zone).toInstant().toEpochMilli()
        val old = store.closedSessionsEndingBefore(cutoffMs)
        if (old.isNotEmpty()) store.rollUp(old.map { it.id }, UsageStats.summarize(old, zone))
        store.deleteSummariesBefore(today.minusDays(SUMMARY_DAYS))
        return old.size
    }
}
