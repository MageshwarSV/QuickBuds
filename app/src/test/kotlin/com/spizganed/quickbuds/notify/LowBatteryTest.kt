package com.spizganed.quickbuds.notify

import com.spizganed.quickbuds.notify.LowBattery.Change
import com.spizganed.quickbuds.notify.LowBattery.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LowBatteryTest {

    private val b = LowBattery(warnAt = 20, criticalAt = 10, rearmAbove = 30)

    @Test fun healthyLevelsSayNothing() {
        assertEquals(Change.NONE, b.onLevel(Part.LEFT, 100, false))
        assertEquals(Change.NONE, b.onLevel(Part.CASE, 45, false))
        assertTrue(b.low.isEmpty())
    }

    @Test fun warnsAtTwentyAndOnceMoreAtTen() {
        assertEquals(Change.NONE, b.onLevel(Part.LEFT, 25, false))
        assertEquals(Change.ALERT, b.onLevel(Part.LEFT, 20, false))
        assertEquals(Change.UPDATE, b.onLevel(Part.LEFT, 15, false))   // the card's level follows, quietly
        assertEquals(Change.NONE, b.onLevel(Part.LEFT, 15, false))
        assertEquals(Change.ALERT, b.onLevel(Part.LEFT, 10, false))
        assertEquals(Change.UPDATE, b.onLevel(Part.LEFT, 5, false))
        assertEquals(mapOf(Part.LEFT to 5), b.low)
    }

    @Test fun namesEveryLowPart() {
        b.onLevel(Part.LEFT, 18, false)
        b.onLevel(Part.CASE, 12, false)
        assertEquals(listOf(Part.LEFT, Part.CASE), b.low.keys.toList())
    }

    @Test fun chargingTakesItOffAndRearms() {
        b.onLevel(Part.RIGHT, 15, false)
        assertEquals(Change.UPDATE, b.onLevel(Part.RIGHT, 16, true))     // in the case, charging
        assertTrue(b.low.isEmpty())
        assertEquals(Change.ALERT, b.onLevel(Part.RIGHT, 19, false))   // out again, still low: said again
    }

    @Test fun aReconnectAtTheSameLevelDoesNotAlertAgain() {
        assertEquals(Change.ALERT, b.onLevel(Part.CASE, 18, false))
        // The lid closed (no case reports), reopened later, same level.
        assertEquals(Change.NONE, b.onLevel(Part.CASE, 18, false))
    }

    @Test fun betweenWarningAndRearmItStaysSaid() {
        assertEquals(Change.ALERT, b.onLevel(Part.LEFT, 19, false))
        assertEquals(Change.UPDATE, b.onLevel(Part.LEFT, 25, false))     // a reading bounced up: off the card
        assertEquals(Change.UPDATE, b.onLevel(Part.LEFT, 19, false))     // back on the card, quietly: already said
        assertEquals(Change.UPDATE, b.onLevel(Part.LEFT, 35, false))     // above 30: re-armed, off the card
        assertEquals(Change.ALERT, b.onLevel(Part.LEFT, 19, false))      // a new drop is said again
    }

    @Test fun unknownLevelChangesNothing() {
        b.onLevel(Part.CASE, 15, false)
        assertEquals(Change.NONE, b.onLevel(Part.CASE, 0, false))       // realme reports 0 with the lid closed
        assertEquals(mapOf(Part.CASE to 15), b.low)
    }
}
