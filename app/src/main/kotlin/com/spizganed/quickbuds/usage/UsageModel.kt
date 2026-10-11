package com.spizganed.quickbuds.usage

import java.time.LocalDate

/**
 * One connection of one device to this phone. Times are wall-clock epoch ms.
 *
 * [endMs] null = the session is still open. [lastSeenMs] is the last time the device was known to be
 * connected: a session left open by a phone restart or a killed process is closed there, not at "now".
 * [startElapsed] and [boot] tie the session to one boot, so its end is measured on the monotonic clock
 * and a wall-clock change while connected does not stretch or shrink it ([UsageClock]).
 */
data class UsageSession(
    val id: Long,
    val deviceId: String,
    val startMs: Long,
    val endMs: Long?,
    val lastSeenMs: Long,
    val startElapsed: Long,
    val boot: BootStamp
)

/** Which boot a session started in: Android's boot count (-1 if the phone does not report it) and the boot's wall time. */
data class BootStamp(val count: Int, val wallAtBoot: Long)

/**
 * A device usage is recorded for: one logical pair of buds (one Bluetooth address, both buds), so left and
 * right are never counted twice. [model] is the name from `models.json` once the buds report their id. [kind] is a
 * [com.spizganed.quickbuds.bluetooth.DeviceCatalog.Kind] name (TWS, NECKBAND, HEADPHONES, SPEAKER), null if unknown.
 */
data class UsageDevice(val id: String, val name: String?, val model: String?, val kind: String? = null) {
    /** The name to show: the model, else the Bluetooth name, else the address. */
    val label: String get() = model ?: name ?: id
}

/**
 * One day of one device, rolled up from sessions older than the retention period ([UsageRetention]).
 * [sessions] counts the sessions that overlap the day, [started] those that began on it.
 */
data class DaySummary(
    val date: LocalDate,
    val deviceId: String,
    val totalMs: Long,
    val sessions: Int,
    val started: Int,
    val longestMs: Long
)

/** A session with its end resolved (an open one ends "now"), the unit every statistic is built from. */
data class UsageSpan(val sessionId: Long, val deviceId: String, val startMs: Long, val endMs: Long, val open: Boolean)

/** One day of usage, all devices or one. [longestMs] is the longest single stretch inside this day. */
data class DayUsage(
    val date: LocalDate,
    val totalMs: Long,
    val sessions: Int,
    val started: Int,
    val longestMs: Long
) {
    val averageSessionMs: Long get() = if (sessions == 0) 0 else totalMs / sessions

    companion object {
        fun empty(date: LocalDate) = DayUsage(date, 0, 0, 0, 0)
    }
}
