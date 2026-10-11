package com.spizganed.quickbuds.usage

import kotlin.math.abs

/** Wall clock, monotonic clock and boot count. The app's reads the system ([Usage]); tests set them by hand. */
interface UsageClock {
    fun wallMs(): Long
    /** Milliseconds since boot, deep sleep included (SystemClock.elapsedRealtime). */
    fun elapsedMs(): Long
    /** Android's boot count, or -1 when the phone does not report it. */
    fun bootCount(): Int
}

/**
 * Turns connection events into sessions. One session per device and connection:
 *
 *  - [onConnected] is idempotent. A repeat (ACL, then A2DP, then our RFCOMM link, then every heartbeat)
 *    only refreshes [UsageSession.lastSeenMs], at most once per [touchEveryMs].
 *  - [onDisconnected] is idempotent. A repeat finds no open session and does nothing.
 *  - A reconnect within [graceMs] of the end reopens that session (a codec change restarts the buds; a
 *    flaky link drops for a second), so a short drop is not a new session.
 *  - A session open from an earlier boot (phone restart, battery pulled) ends at its last-seen time.
 *  - In one boot, a session's end is measured on the monotonic clock from its start, so changing the
 *    clock or the time zone while connected does not change its length.
 *
 * Only the logical device is tracked (one Bluetooth address = both buds), so left and right never add up.
 * Not thread-safe: [Usage] calls it from one thread.
 */
class UsageTracker(
    private val store: UsageStore,
    private val clock: UsageClock,
    val graceMs: Long = GRACE_MS,
    private val touchEveryMs: Long = TOUCH_EVERY_MS
) {

    /** Device rows already written in this process, so a heartbeat does not rewrite an unchanged one. */
    private val known = HashMap<String, UsageDevice>()

    /**
     * The device is connected (a connect event or any sign of life). Starts a session if none is open.
     *
     * [newLink]: the system reports a new link (ACL connected), so any link before it has ended. With a
     * session still open from more than [graceMs] ago, its disconnect was missed (QuickBuds was force
     * stopped, which blocks broadcasts): that session ends at its last-seen time.
     *
     * Returns true when a session started, reopened or ended: what a statistics screen shows changed.
     */
    fun onConnected(device: UsageDevice, newLink: Boolean = false): Boolean {
        if (known[device.id] != device) {
            store.upsertDevice(device)
            known[device.id] = device
        }
        val open = store.openSession(device.id)
        if (open != null) {
            if (sameBoot(open)) {
                val now = nowFor(open)
                if (!newLink || now - open.lastSeenMs <= graceMs) {
                    if (now - open.lastSeenMs >= touchEveryMs) store.touchSession(open.id, now)
                    return false
                }
            }
            // Left open by a restart or a missed disconnect: it ended when the device was last seen.
            store.closeSession(open.id, open.lastSeenMs)
        }
        val last = store.lastClosedSession(device.id)
        if (last != null && last.endMs != null && sameBoot(last)) {
            val gap = nowFor(last) - last.endMs
            if (gap in 0..graceMs) {
                store.reopenSession(last.id, nowFor(last))
                return true
            }
        }
        val stamp = bootStamp()
        store.insertSession(
            UsageSession(0, device.id, clock.wallMs(), null, clock.wallMs(), clock.elapsedMs(), stamp)
        )
        return true
    }

    /** The whole device disconnected. Does nothing when no session is open. */
    fun onDisconnected(deviceId: String) {
        val open = store.openSession(deviceId) ?: return
        close(open)
    }

    /** Bluetooth turned off: every open session ends now. */
    fun onAllDisconnected() {
        store.openSessions().forEach { close(it) }
    }

    /**
     * After the process starts: closes what cannot still be open. A session from an earlier boot ends at
     * its last-seen time; one whose device is not in [connected] (null = unknown, skip that check) also
     * ends there, since nothing saw it after that.
     */
    fun reconcile(connected: Set<String>?) {
        for (open in store.openSessions()) {
            when {
                !sameBoot(open) -> store.closeSession(open.id, open.lastSeenMs)
                connected != null && open.deviceId !in connected -> store.closeSession(open.id, open.lastSeenMs)
            }
        }
    }

    /** The open sessions as spans ending now, for statistics. */
    fun resolve(session: UsageSession): UsageSpan {
        val end = session.endMs ?: if (sameBoot(session)) nowFor(session) else session.lastSeenMs
        return UsageSpan(session.id, session.deviceId, session.startMs, maxOf(end, session.startMs), session.endMs == null)
    }

    private fun close(open: UsageSession) {
        val end = if (sameBoot(open)) nowFor(open) else open.lastSeenMs
        store.closeSession(open.id, maxOf(end, open.startMs))
    }

    /** "Now" on the session's own timeline: its start plus the monotonic time since. */
    private fun nowFor(s: UsageSession): Long = s.startMs + (clock.elapsedMs() - s.startElapsed).coerceAtLeast(0)

    private fun bootStamp() = BootStamp(clock.bootCount(), clock.wallMs() - clock.elapsedMs())

    /**
     * True when [s] started in this boot. The boot count decides when both sides have one. Without it: the
     * monotonic clock must not have gone back, and the boot's wall time must match (a clock change moves
     * it, so a generous tolerance).
     */
    fun sameBoot(s: UsageSession): Boolean {
        val count = clock.bootCount()
        if (count >= 0 && s.boot.count >= 0) return count == s.boot.count
        if (clock.elapsedMs() < s.startElapsed) return false
        return abs(clock.wallMs() - clock.elapsedMs() - s.boot.wallAtBoot) < BOOT_TOLERANCE_MS
    }

    companion object {
        /** A reconnect this soon after a drop continues the session. */
        const val GRACE_MS = 60_000L
        /** Last-seen writes at most this often: one row update a minute while connected. */
        const val TOUCH_EVERY_MS = 60_000L
        private const val BOOT_TOLERANCE_MS = 10 * 60_000L
    }
}
