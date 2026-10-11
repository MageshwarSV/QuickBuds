package com.spizganed.quickbuds.usage

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** A clock the test moves by hand. [reboot] starts a new boot: the monotonic clock starts over. */
class FakeClock(var wall: Long, var elapsed: Long = 1_000_000L, var boot: Int = 1) : UsageClock {
    override fun wallMs() = wall
    override fun elapsedMs() = elapsed
    override fun bootCount() = boot

    /** Real time passes: both clocks move. */
    fun advance(ms: Long) { wall += ms; elapsed += ms }

    /** The phone restarts [downMs] later; the new boot has been up for [upMs]. */
    fun reboot(downMs: Long = 60_000L, upMs: Long = 30_000L) { wall += downMs + upMs; elapsed = upMs; boot++ }

    companion object {
        fun at(zone: ZoneId, dateTime: String) = FakeClock(ms(zone, dateTime))
    }
}

fun ms(zone: ZoneId, dateTime: String): Long = LocalDateTime.parse(dateTime).atZone(zone).toInstant().toEpochMilli()

const val MIN = 60_000L
const val HOUR = 60 * MIN

/** [UsageStore] in memory, with the database's semantics (closing twice keeps the first end, roll-ups merge). */
class InMemoryUsageStore : UsageStore {
    private val devices = LinkedHashMap<String, UsageDevice>()
    private val caps = HashMap<String, UsageCapabilities>()
    private val sessions = LinkedHashMap<Long, UsageSession>()
    private val summaries = LinkedHashMap<Pair<LocalDate, String>, DaySummary>()
    private var nextId = 1L
    var writes = 0
        private set

    override fun upsertDevice(device: UsageDevice) {
        writes++
        val old = devices[device.id]
        devices[device.id] = UsageDevice(device.id, device.name ?: old?.name, device.model ?: old?.model, device.kind ?: old?.kind)
    }

    override fun devices() = devices.values.toList()
    override fun capabilities(deviceId: String) = caps[deviceId] ?: UsageCapabilities()
    override fun saveCapabilities(deviceId: String, caps: UsageCapabilities) { this.caps[deviceId] = caps }

    override fun openSession(deviceId: String) = sessions.values.lastOrNull { it.deviceId == deviceId && it.endMs == null }
    override fun openSessions() = sessions.values.filter { it.endMs == null }
    override fun lastClosedSession(deviceId: String) =
        sessions.values.filter { it.deviceId == deviceId && it.endMs != null }.maxByOrNull { it.endMs!! }

    override fun insertSession(session: UsageSession): Long {
        writes++
        val id = nextId++
        sessions[id] = session.copy(id = id)
        return id
    }

    override fun closeSession(id: Long, endMs: Long) {
        writes++
        val s = sessions[id] ?: return
        if (s.endMs == null) sessions[id] = s.copy(endMs = endMs, lastSeenMs = endMs)
    }

    override fun reopenSession(id: Long, lastSeenMs: Long) {
        writes++
        sessions[id]?.let { sessions[id] = it.copy(endMs = null, lastSeenMs = lastSeenMs) }
    }

    override fun touchSession(id: Long, lastSeenMs: Long) {
        writes++
        sessions[id]?.takeIf { it.endMs == null }?.let { sessions[id] = it.copy(lastSeenMs = lastSeenMs) }
    }

    override fun sessions() = sessions.values.sortedBy { it.startMs }
    override fun closedSessionsEndingBefore(ms: Long) = sessions.values.filter { it.endMs != null && it.endMs!! < ms }

    override fun rollUp(sessionIds: List<Long>, summaries: List<DaySummary>) {
        for (s in summaries) {
            val key = s.date to s.deviceId
            val old = this.summaries[key]
            this.summaries[key] = if (old == null) s else old.copy(
                totalMs = old.totalMs + s.totalMs, sessions = old.sessions + s.sessions,
                started = old.started + s.started, longestMs = maxOf(old.longestMs, s.longestMs)
            )
        }
        sessionIds.forEach { sessions.remove(it) }
    }

    override fun summaries() = summaries.values.sortedBy { it.date }
    override fun deleteSummariesBefore(date: LocalDate) { summaries.keys.removeAll { it.first.isBefore(date) } }
}

/** Everything as statistics see it: spans resolved by [tracker], plus the summaries. */
fun UsageStore.days(tracker: UsageTracker, zone: ZoneId, deviceId: String? = null) =
    UsageStats.byDay(sessions().map { tracker.resolve(it) }, summaries(), zone, deviceId)
