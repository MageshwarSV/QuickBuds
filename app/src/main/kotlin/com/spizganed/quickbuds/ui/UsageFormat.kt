package com.spizganed.quickbuds.ui

import android.content.Context
import com.spizganed.quickbuds.R
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/** Durations, dates and times for the usage screens, in the app's language. */
object UsageFormat {

    private const val MINUTE = 60_000L

    /** "3h 42m", "50m": rounded to the minute. */
    fun duration(c: Context, ms: Long): String {
        val minutes = (ms + MINUTE / 2) / MINUTE
        val h = minutes / 60
        val m = minutes % 60
        return if (h == 0L) c.getString(R.string.usage_m, m) else c.getString(R.string.usage_hm, h, m)
    }

    /** A y-axis value: "2h", or "30m" under an hour. */
    fun axis(c: Context, ms: Long): String {
        val minutes = ms / MINUTE
        return if (minutes >= 60 && minutes % 60 == 0L) c.getString(R.string.usage_h, minutes / 60)
            else c.getString(R.string.usage_m, minutes)
    }

    /** "31 days", with the language's plural forms. */
    fun days(c: Context, n: Int): String = c.resources.getQuantityString(R.plurals.settings_retention_value, n, n)

    /** "09:15 – 10:05", "Oct 2 – Oct 8". */
    fun range(c: Context, from: String, to: String): String = c.getString(R.string.usage_range, from, to)

    fun locale(c: Context): Locale = c.resources.configuration.locales[0]

    /** "Oct 8, 2026". */
    fun day(c: Context, date: LocalDate): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale(c)).format(date)

    /** "Wed, Oct 8": the day detail's heading and the chart bubble. */
    fun dayWithWeekday(c: Context, date: LocalDate): String = pattern(c, "EEEMMMd").format(date)

    /** "Oct 8": a range end. */
    fun dayShort(c: Context, date: LocalDate): String = pattern(c, "MMMd").format(date)

    /** "October 2026". */
    fun month(c: Context, month: YearMonth): String = pattern(c, "MMMMyyyy").format(month)

    /** "Mon": a bar label. */
    fun weekday(c: Context, date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale(c))

    /** "09:15" or "9:15 AM", as the phone shows times. */
    fun time(c: Context, ms: Long): String = android.text.format.DateFormat.getTimeFormat(c).format(Date(ms))

    private fun pattern(c: Context, skeleton: String): DateTimeFormatter {
        val l = locale(c)
        return DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(l, skeleton), l)
    }
}
