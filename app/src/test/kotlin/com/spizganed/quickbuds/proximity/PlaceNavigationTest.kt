package com.spizganed.quickbuds.proximity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceNavigationTest {

    @Test fun distanceAlongAMeridian() {
        // One thousandth of a degree of latitude is about 111.2 m.
        assertEquals(111.2, PlaceNavigation.distanceM(13.0, 80.0, 13.001, 80.0), 0.2)
    }

    @Test fun distanceBetweenTwoCities() {
        // Chennai to Bengaluru, about 290 km in a straight line.
        assertEquals(290_000.0, PlaceNavigation.distanceM(13.0827, 80.2707, 12.9716, 77.5946), 3_000.0)
    }

    @Test fun samePointIsZero() {
        assertEquals(0.0, PlaceNavigation.distanceM(13.0, 80.0, 13.0, 80.0), 1e-9)
    }

    @Test fun bearings() {
        assertEquals(0.0, PlaceNavigation.bearingDeg(13.0, 80.0, 13.01, 80.0), 0.01)    // north
        assertEquals(90.0, PlaceNavigation.bearingDeg(0.0, 80.0, 0.0, 80.01), 0.01)     // east, on the equator
        assertEquals(180.0, PlaceNavigation.bearingDeg(13.0, 80.0, 12.99, 80.0), 0.01)  // south
        assertEquals(270.0, PlaceNavigation.bearingDeg(0.0, 80.0, 0.0, 79.99), 0.01)    // west
    }

    @Test fun theArrowIsTheBearingSeenFromTheHeading() {
        assertEquals(0.0, PlaceNavigation.relativeDeg(90.0, 90.0), 1e-9)      // straight ahead
        assertEquals(90.0, PlaceNavigation.relativeDeg(90.0, 0.0), 1e-9)      // to the right
        assertEquals(-90.0, PlaceNavigation.relativeDeg(0.0, 90.0), 1e-9)     // to the left
        assertEquals(-20.0, PlaceNavigation.relativeDeg(350.0, 10.0), 1e-9)   // across north
        assertEquals(20.0, PlaceNavigation.relativeDeg(10.0, 350.0), 1e-9)
    }

    @Test fun theMarginCombinesBothAccuracies() {
        assertEquals(10.0, PlaceNavigation.uncertaintyM(6f, 8f)!!, 1e-6)
        assertNull(PlaceNavigation.uncertaintyM(null, 8f))
    }

    @Test fun nearWhenInsideTheMarginNeverZero() {
        assertEquals(PlaceNavigation.State.NEAR, PlaceNavigation.state(9.0, 10.0))
        assertEquals(PlaceNavigation.State.NEAR, PlaceNavigation.state(16.0, 10.0))   // inside the 95% radius
        assertEquals(PlaceNavigation.State.GO, PlaceNavigation.state(42.0, 10.0))
        // 2026-10-16: the phone never moved; saved ±100 m (Wi-Fi), now ±5 m (GPS), 152 m apart. Near, not "152 m".
        assertEquals(PlaceNavigation.State.NEAR, PlaceNavigation.state(152.0, PlaceNavigation.uncertaintyM(5f, 100f)))
        assertEquals(PlaceNavigation.State.NEAR, PlaceNavigation.state(5.0, 2.0))     // margin floor 8 m
        assertEquals(PlaceNavigation.State.NEAR, PlaceNavigation.state(7.0, null))
    }

    @Test fun metresThenKilometres() {
        assertEquals(false to "42", PlaceNavigation.splitDistance(42.4))
        assertEquals(true to "1.3", PlaceNavigation.splitDistance(1_260.0))
    }

    @Test fun confidenceOfASavedPosition() {
        val at = 100 * 60_000L
        fun c(acc: Float?, ageMin: Long) = DisconnectPlaceRules.confidence(Fix(1.0, 1.0, acc, at - ageMin * 60_000L), at)
        assertEquals(DisconnectPlaceRules.Confidence.GOOD, c(10f, 1))
        assertEquals(DisconnectPlaceRules.Confidence.FAIR, c(60f, 5))
        assertEquals(DisconnectPlaceRules.Confidence.POOR, c(300f, 1))
        assertEquals(DisconnectPlaceRules.Confidence.POOR, c(null, 1))
        assertEquals(DisconnectPlaceRules.Confidence.STALE, c(5f, 20))
    }

    @Test fun theTargetPrefersAGoodPositionWhileConnected() {
        val at = 100 * 60_000L
        val connected = Fix(2.0, 2.0, 10f, at - 30_000L)
        val p = DisconnectPlace(at, "A", "Buds", lat = 3.0, lng = 3.0, accuracyM = 20f, fixAtMs = at + 5_000L, connected = connected)
        assertEquals(connected, p.target)
        // A stale one while connected: the position after the disconnect.
        val stale = p.copy(connected = Fix(2.0, 2.0, 10f, at - 30 * 60_000L))
        assertEquals(3.0, stale.target!!.lat, 0.0)
        // A rough one while connected (Wi-Fi, ±100 m) and a GPS one after (±10 m): the GPS one.
        val rough = p.copy(connected = Fix(2.0, 2.0, 100f, at - 30_000L), accuracyM = 10f)
        assertEquals(3.0, rough.target!!.lat, 0.0)
    }
}
