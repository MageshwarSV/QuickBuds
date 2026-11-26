package com.spizganed.quickbuds.findphone

import com.spizganed.quickbuds.protocol.OpoProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindPhoneTest {

    private val L = 1
    private val R = 2
    private val SINGLE = 1
    private val DOUBLE = 2
    private val TRIPLE = 3
    private val HOLD = 4

    private fun run(vararg events: Triple<Int, Int, Long>): List<Boolean> {
        val c = FindPhone.TapCounter()
        return events.map { (side, act, at) -> c.feed(side, act, at) }
    }

    @Test fun fourSinglesTrigger() {
        assertEquals(listOf(false, false, false, true),
            run(Triple(L, SINGLE, 0), Triple(L, SINGLE, 400), Triple(L, SINGLE, 800), Triple(L, SINGLE, 1200)))
    }

    @Test fun twoDoublesOrATripleAndASingleTrigger() {
        assertEquals(listOf(false, true), run(Triple(R, DOUBLE, 0), Triple(R, DOUBLE, 700)))
        assertEquals(listOf(false, true), run(Triple(R, TRIPLE, 0), Triple(R, SINGLE, 600)))
    }

    @Test fun threeTapsDoNot() {
        assertEquals(listOf(false), run(Triple(L, TRIPLE, 0)))
        assertEquals(listOf(false, false, false), run(Triple(L, SINGLE, 0), Triple(L, SINGLE, 300), Triple(L, SINGLE, 700)))
    }

    @Test fun tapsOutsideTheWindowDoNot() {
        assertEquals(listOf(false, false), run(Triple(L, DOUBLE, 0), Triple(L, DOUBLE, FindPhone.WINDOW_MS + 1)))
    }

    @Test fun aDoubleTapReportedTwiceCountsOnce() {
        // PROTOCOL.md §6: one double tap can report twice.
        assertEquals(listOf(false, false), run(Triple(R, DOUBLE, 0), Triple(R, DOUBLE, 100)))
    }

    @Test fun theOtherEarbudOrAHoldStartsAgain() {
        assertEquals(listOf(false, false), run(Triple(L, DOUBLE, 0), Triple(R, DOUBLE, 500)))
        assertEquals(listOf(false, false, false), run(Triple(L, DOUBLE, 0), Triple(L, HOLD, 300), Triple(L, DOUBLE, 900)))
    }

    @Test fun afterATriggerTheCountStartsAgain() {
        assertEquals(listOf(false, true, false), run(Triple(L, DOUBLE, 0), Triple(L, DOUBLE, 600), Triple(L, DOUBLE, 1200)))
    }

    @Test fun theGestureEventIsSubscribedOnlyWhenOnAndOffered() {
        val offered = setOf(0x01, 0x02, 0x03, 0x08, 0xF1)
        assertTrue(0xF1 in OpoProtocol.notifyIds(false, false, false, offered, gestures = true))
        assertFalse(0xF1 in OpoProtocol.notifyIds(false, false, false, offered, gestures = false))
        // Not offered (or not known): never subscribed.
        assertFalse(0xF1 in OpoProtocol.notifyIds(false, false, false, setOf(0x01, 0x02, 0x03), gestures = true))
        assertFalse(0xF1 in OpoProtocol.notifyIds(false, false, false, null, gestures = true))
    }
}
