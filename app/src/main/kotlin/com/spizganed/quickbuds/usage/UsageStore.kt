package com.spizganed.quickbuds.usage

import java.time.LocalDate

/**
 * Where usage lives. [UsageDatabase] is the app's (SQLite); tests use an in-memory one. Every method
 * is called from one thread ([Usage]'s executor), so an implementation needs no locking of its own.
 */
interface UsageStore {
    fun upsertDevice(device: UsageDevice)
    fun devices(): List<UsageDevice>

    /** What a device was seen to report; see [UsageCapabilities]. */
    fun capabilities(deviceId: String): UsageCapabilities
    fun saveCapabilities(deviceId: String, caps: UsageCapabilities)

    fun openSession(deviceId: String): UsageSession?
    fun openSessions(): List<UsageSession>
    /** The most recently ended session of a device, or null. */
    fun lastClosedSession(deviceId: String): UsageSession?
    /** Returns the new id. */
    fun insertSession(session: UsageSession): Long
    fun closeSession(id: Long, endMs: Long)
    /** Reopens a session that ended moments ago (a reconnect within [UsageTracker.graceMs]). */
    fun reopenSession(id: Long, lastSeenMs: Long)
    fun touchSession(id: Long, lastSeenMs: Long)

    /** Every session still held in detail, oldest first. */
    fun sessions(): List<UsageSession>
    /** Closed sessions that ended before [ms]: the ones retention rolls up. */
    fun closedSessionsEndingBefore(ms: Long): List<UsageSession>

    /** In one transaction: add [summaries] to the stored day summaries (merging same day and device), delete [sessionIds]. */
    fun rollUp(sessionIds: List<Long>, summaries: List<DaySummary>)
    fun summaries(): List<DaySummary>
    fun deleteSummariesBefore(date: LocalDate)
}

/** Per-device facts learned at runtime. Null = not known yet. */
data class UsageCapabilities(
    /** The buds sent a wear report (in ear / out / in case) since they were first seen. */
    val placementReports: Boolean? = null,
    /** This phone could read the link's signal strength (RSSI) for these buds. */
    val rssi: Boolean? = null
)
