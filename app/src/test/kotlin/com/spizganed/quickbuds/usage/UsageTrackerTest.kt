package com.spizganed.quickbuds.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class UsageTrackerTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val budsA = UsageDevice("AA:AA:AA:AA:AA:01", "OnePlus Nord Buds 3R", "OnePlus Nord Buds 3R")
    private val budsB = UsageDevice("BB:BB:BB:BB:BB:02", "OPPO Enco Air", null)
    private val clock = FakeClock.at(zone, "2026-10-08T09:15")
    private val store = InMemoryUsageStore()
    private val tracker = UsageTracker(store, clock)

    private fun only(): UsageSession = store.sessions().single()
    private fun duration(s: UsageSession) = s.endMs!! - s.startMs

    // 1
    @Test fun connectStartsSession() {
        tracker.onConnected(budsA, newLink = true)
        val s = store.openSession(budsA.id)
        assertNotNull(s)
        assertEquals(clock.wall, s!!.startMs)
        assertEquals(listOf(budsA), store.devices())
    }

    // 2
    @Test fun disconnectEndsSessionAndSavesDuration() {
        tracker.onConnected(budsA, newLink = true)
        clock.advance(50 * MIN)
        tracker.onDisconnected(budsA.id)
        assertNull(store.openSession(budsA.id))
        assertEquals(50 * MIN, duration(only()))
    }

    // 3
    @Test fun duplicateConnectedMakesOneSession() {
        tracker.onConnected(budsA, newLink = true)   // ACL
        tracker.onConnected(budsA)                   // A2DP
        tracker.onConnected(budsA)                   // our RFCOMM link
        clock.advance(5_000)
        tracker.onConnected(budsA, newLink = true)   // a duplicate ACL broadcast
        assertEquals(1, store.sessions().size)
    }

    // 4
    @Test fun duplicateDisconnectedEndsOnce() {
        tracker.onConnected(budsA, newLink = true)
        clock.advance(30 * MIN)
        tracker.onDisconnected(budsA.id)
        clock.advance(10 * MIN)
        tracker.onDisconnected(budsA.id)
        tracker.onDisconnected(budsA.id)
        assertEquals(1, store.sessions().size)
        assertEquals(30 * MIN, duration(only()))
    }

    // 5
    @Test fun rapidReconnectContinuesTheSession() {
        tracker.onConnected(budsA, newLink = true)
        clock.advance(30 * MIN)
        tracker.onDisconnected(budsA.id)
        clock.advance(20_000)                        // the buds restart after a codec change
        tracker.onConnected(budsA, newLink = true)
        clock.advance(10 * MIN)
        tracker.onDisconnected(budsA.id)
        assertEquals(1, store.sessions().size)
        assertEquals(40 * MIN + 20_000, duration(only()))
    }

    @Test fun reconnectAfterTheGraceIsANewSession() {
        tracker.onConnected(budsA, newLink = true)
        clock.advance(30 * MIN)
        tracker.onDisconnected(budsA.id)
        clock.advance(5 * MIN)
        tracker.onConnected(budsA, newLink = true)
        assertEquals(2, store.sessions().size)
        assertEquals(30 * MIN, duration(store.sessions().first()))
    }

    // 6
    @Test fun bluetoothOffEndsEverySessionAndOnStartsNewOnes() {
        tracker.onConnected(budsA, newLink = true)
        tracker.onConnected(budsB, newLink = true)
        clock.advance(20 * MIN)
        tracker.onAllDisconnected()
        assertTrue(store.openSessions().isEmpty())
        store.sessions().forEach { assertEquals(20 * MIN, duration(it)) }
        clock.advance(10 * MIN)
        tracker.onConnected(budsA, newLink = true)
        assertEquals(3, store.sessions().size)
        assertNotNull(store.openSession(budsA.id))
    }

    @Test fun bluetoothOffAndOnWithinTheGraceContinues() {
        tracker.onConnected(budsA, newLink = true)
        clock.advance(20 * MIN)
        tracker.onAllDisconnected()
        clock.advance(15_000)
        tracker.onConnected(budsA, newLink = true)
        assertEquals(1, store.sessions().size)
    }

    // 7
    @Test fun appRestartKeepsTheOpenSession() {
        tracker.onConnected(budsA, newLink = true)
        clock.advance(10 * MIN)
        // The process dies and starts again: a new tracker on the same database.
        val restarted = UsageTracker(store, clock)
        restarted.reconcile(setOf(budsA.id))
        restarted.onConnected(budsA)                 // our link comes back up
        clock.advance(5 * MIN)
        restarted.onDisconnected(budsA.id)
        assertEquals(1, store.sessions().size)
        assertEquals(15 * MIN, duration(only()))
    }

    @Test fun appRestartWithTheBudsGoneEndsAtLastSeen() {
        tracker.onConnected(budsA, newLink = true)
        clock.advance(4 * MIN)
        tracker.onConnected(budsA)                   // heartbeat: last seen at 4 min
        clock.advance(30 * MIN)
        UsageTracker(store, clock).reconcile(emptySet())
        assertEquals(4 * MIN, duration(only()))
    }

    // 8
    @Test fun phoneRestartEndsTheOldSessionAtLastSeen() {
        tracker.onConnected(budsA, newLink = true)
        clock.advance(4 * MIN)
        tracker.onConnected(budsA)                   // heartbeat
        clock.advance(50_000)
        clock.reboot()
        val afterBoot = UsageTracker(store, clock)
        afterBoot.onConnected(budsA, newLink = true)
        val (old, new) = store.sessions()
        assertEquals(4 * MIN, duration(old))
        assertNull(new.endMs)
        assertEquals(clock.wall, new.startMs)
    }

    @Test fun phoneRestartSessionResolvesToLastSeenUntilReconciled() {
        tracker.onConnected(budsA, newLink = true)
        clock.advance(4 * MIN)
        tracker.onConnected(budsA)
        clock.reboot()
        val afterBoot = UsageTracker(store, clock)
        val span = afterBoot.resolve(only())
        assertEquals(4 * MIN, span.endMs - span.startMs)
        afterBoot.reconcile(null)
        assertEquals(4 * MIN, duration(only()))
    }

    @Test fun missedDisconnectIsClosedByTheNextNewLink() {
        tracker.onConnected(budsA, newLink = true)
        clock.advance(10 * MIN)
        tracker.onConnected(budsA)                   // last seen at 10 min
        clock.advance(50 * MIN)                      // force stopped: the disconnect never arrived
        tracker.onConnected(budsA, newLink = true)
        val (old, new) = store.sessions()
        assertEquals(10 * MIN, duration(old))
        assertNull(new.endMs)
    }

    // 9
    @Test fun leftAndRightAreOneDeviceAndNeverAddUp() {
        tracker.onConnected(budsA, newLink = true)
        // Reports from the left bud, then the right one, every few minutes for an hour: same device.
        repeat(12) {
            clock.advance(5 * MIN)
            tracker.onConnected(budsA)
        }
        tracker.onDisconnected(budsA.id)
        assertEquals(1, store.sessions().size)
        val day = store.days(tracker, zone)[LocalDate.of(2026, 10, 8)]!!
        assertEquals(HOUR, day.totalMs)
    }

    @Test fun onlyAStartOrReopenReportsAChange() {
        assertTrue(tracker.onConnected(budsA, newLink = true))
        clock.advance(2 * MIN)
        assertEquals(false, tracker.onConnected(budsA))            // heartbeat
        tracker.onDisconnected(budsA.id)
        clock.advance(10_000)
        assertTrue(tracker.onConnected(budsA, newLink = true))     // reopened within the grace
    }

    @Test fun heartbeatsWriteAtMostOncePerMinute() {
        tracker.onConnected(budsA, newLink = true)
        val before = store.writes
        repeat(30) { clock.advance(1_000); tracker.onConnected(budsA) }
        assertEquals(before, store.writes)
        clock.advance(31_000)
        tracker.onConnected(budsA)
        assertEquals(before + 1, store.writes)
    }

    @Test fun clockChangeWhileConnectedDoesNotChangeTheLength() {
        tracker.onConnected(budsA, newLink = true)
        clock.elapsed += 30 * MIN
        clock.wall += 3 * HOUR + 30 * MIN            // 30 min pass, and the user moves the clock 3 h ahead
        tracker.onDisconnected(budsA.id)
        assertEquals(30 * MIN, duration(only()))
    }

    // 13
    @Test fun multipleDevicesAreKeptApart() {
        clock.wall = ms(zone, "2026-10-08T08:00")
        tracker.onConnected(budsA, newLink = true)
        clock.advance(2 * HOUR)
        tracker.onDisconnected(budsA.id)
        clock.advance(4 * HOUR)                      // 14:00
        tracker.onConnected(budsB, newLink = true)
        clock.advance(90 * MIN)
        tracker.onDisconnected(budsB.id)

        val day = LocalDate.of(2026, 10, 8)
        assertEquals(3 * HOUR + 30 * MIN, store.days(tracker, zone)[day]!!.totalMs)
        assertEquals(2 * HOUR, store.days(tracker, zone, budsA.id)[day]!!.totalMs)
        assertEquals(90 * MIN, store.days(tracker, zone, budsB.id)[day]!!.totalMs)
        assertEquals(2, store.days(tracker, zone)[day]!!.sessions)
    }

    @Test fun twoDevicesConnectedTogetherEachGetTheirOwnSession() {
        tracker.onConnected(budsA, newLink = true)
        clock.advance(10 * MIN)
        tracker.onConnected(budsB, newLink = true)
        clock.advance(10 * MIN)
        tracker.onDisconnected(budsA.id)
        assertNotNull(store.openSession(budsB.id))
        assertEquals(20 * MIN, duration(store.sessions().first { it.deviceId == budsA.id }))
    }

    @Test fun openSessionCountsUpToNow() {
        tracker.onConnected(budsA, newLink = true)
        clock.advance(42 * MIN)
        val span = tracker.resolve(only())
        assertTrue(span.open)
        assertEquals(42 * MIN, span.endMs - span.startMs)
    }
}
