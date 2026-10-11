package com.spizganed.quickbuds.proximity

import com.spizganed.quickbuds.proximity.CaseReminder.Effect
import com.spizganed.quickbuds.proximity.CaseReminder.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaseReminderTest {

    private val r = CaseReminder(delayMs = 20_000)
    private var t = 0L

    private fun wear(left: Int, right: Int) = r.onWear(left, right, t)
    private fun after(ms: Long): Effect { t += ms; return r.check(t) }

    @Test fun oneInTheCaseAndOneOutNotifiesAfterTheDelay() {
        wear(1, 1)
        wear(4, 1)                                    // left goes in, right stays on the table
        assertEquals(Effect.None, after(10_000))      // still time to put it in
        assertEquals(Effect.Notify(Side.RIGHT), after(10_000))
    }

    @Test fun namesTheBudLeftOut() {
        wear(1, 4)
        assertEquals(Effect.Notify(Side.LEFT), after(20_000))
    }

    @Test fun secondBudInInTimeMeansNoReminder() {
        wear(4, 1)
        t += 8_000
        wear(4, 4)
        assertEquals(Effect.None, after(20_000))
        assertNull(r.dueAt)
    }

    @Test fun listeningWithOneBudIsNotAReminder() {
        wear(4, 3)                                    // right in the ear
        assertEquals(Effect.None, after(60_000))
        wear(4, 7)
        assertEquals(Effect.None, after(60_000))
    }

    @Test fun notifiesOnlyOnce() {
        wear(4, 1)
        assertEquals(Effect.Notify(Side.RIGHT), after(20_000))
        wear(4, 1)
        wear(4, 5)
        assertEquals(Effect.None, after(60_000))
    }

    @Test fun clearsWhenTheSecondBudGoesIn() {
        wear(4, 1)
        after(20_000)
        assertEquals(Effect.Clear, wear(4, 4))
    }

    @Test fun clearsWhenTheOutsideBudIsWorn() {
        wear(4, 1)
        after(20_000)
        assertEquals(Effect.Clear, wear(4, 3))
    }

    @Test fun clearsWhenBothComeOut() {
        wear(4, 1)
        after(20_000)
        assertEquals(Effect.Clear, wear(1, 1))
    }

    @Test fun closedLidStillCountsTheCaseBudAsIn() {
        wear(4, 1)
        t += 5_000
        wear(0, 1)                                    // lid closed: the case bud is off
        assertEquals(Effect.Notify(Side.RIGHT), after(15_000))
    }

    @Test fun budsThatReportOffInTheCaseStillGetTheReminder() {
        // OnePlus Nord Buds 3r, captured 2026-10-09: right bud into the case = R "off" (0), never 4.
        wear(1, 1)
        wear(1, 0)
        assertEquals(Effect.None, after(10_000))
        assertEquals(Effect.Notify(Side.LEFT), after(10_000))
        assertEquals(Effect.Clear, wear(1, 1))        // taken out again (00:48:42 in the capture)
    }

    @Test fun aBriefOffDuringAReconnectDoesNotNotify() {
        wear(1, 0)
        t += 1_000
        wear(1, 1)                                    // the second bud reports back a second later
        assertEquals(Effect.None, after(30_000))
    }

    @Test fun allZerosBeforeTheLinkDropsDoesNotNotify() {
        wear(4, 4)
        wear(0, 0)                                    // lid closing with both inside
        assertEquals(Effect.None, after(60_000))
    }

    @Test fun switchingSidesRestartsTheWait() {
        wear(4, 1)
        t += 15_000
        wear(1, 4)                                    // swapped which bud is in the case
        assertEquals(Effect.None, after(10_000))
        assertEquals(Effect.Notify(Side.LEFT), after(10_000))
    }

    @Test fun disconnectDropsAPendingReminderButKeepsAShownOne() {
        wear(4, 1)
        r.onDisconnected()
        assertEquals(Effect.None, after(60_000))
        wear(4, 1)
        after(20_000)
        r.onDisconnected()
        assertEquals(Effect.Clear, wear(4, 4))        // next link: both in, the shown one clears
    }

    @Test fun turningItOffClearsAShownReminder() {
        wear(4, 1)
        after(20_000)
        assertEquals(Effect.Clear, r.stop())
    }
}
