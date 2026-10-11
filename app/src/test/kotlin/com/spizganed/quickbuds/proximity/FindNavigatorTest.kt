package com.spizganed.quickbuds.proximity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FindNavigatorTest {

    // ---------- Broadcast 1 m level ----------

    @Test fun aStatedTransmitPowerGivesTheOneMetreLevel() = assertEquals(-59.0, BroadcastDistance.atOneMetre(-18), 0.001)

    @Test fun anImpossibleTransmitPowerIsIgnored() {
        assertEquals(BroadcastDistance.DEFAULT_AT_ONE_METRE_DBM, BroadcastDistance.atOneMetre(-127), 0.0)
        assertEquals(BroadcastDistance.DEFAULT_AT_ONE_METRE_DBM, BroadcastDistance.atOneMetre(127), 0.0)
        assertEquals(BroadcastDistance.DEFAULT_AT_ONE_METRE_DBM, BroadcastDistance.atOneMetre(null), 0.0)
    }

    // ---------- Whose broadcast ----------

    private fun advert(l: Int, r: Int, c: Int) = com.spizganed.quickbuds.bluetooth.FastPairBattery.Levels(l, r, c, false, false, false)

    @Test fun theBudsOwnLevelsMatchTheirBroadcast() {
        assertTrue(BroadcastMatch.buds(advert(-1, 70, -1), left = -1, right = 70, case = -1))   // captured: R=70
        assertTrue(BroadcastMatch.buds(advert(80, 75, 50), left = 81, right = 75, case = 50))
    }

    @Test fun someoneElsesBroadcastDoesNotMatch() {
        assertTrue(!BroadcastMatch.buds(advert(20, 25, 90), left = 80, right = 75, case = 50))
        assertTrue(!BroadcastMatch.buds(advert(80, 25, 50), left = 80, right = 75, case = 50))   // one part off
    }

    @Test fun nothingToCompareIsNoMatch() {
        assertTrue(!BroadcastMatch.buds(advert(-1, -1, 60), left = 80, right = 75, case = -1))
        assertTrue(!BroadcastMatch.buds(advert(80, 75, 50), left = -1, right = -1, case = -1))
    }

    @Test fun anotherBrandMatchesByItsOneLevel() {
        assertTrue(BroadcastMatch.single(advert(70, 68, 40), level = 70))
        assertTrue(!BroadcastMatch.single(advert(20, 25, 40), level = 70))
        assertTrue(!BroadcastMatch.single(advert(70, 68, 40), level = -1))
    }

    // ---------- Following the broadcast ----------

    @Test fun aMatchingBroadcastIsFollowed() {
        val t = BroadcastTracker(severalAllowed = true)
        assertEquals(-40, t.onAdvert("A", -40, -18, matches = true, nowMs = 0)!!.rssi)
    }

    @Test fun aStrangersBroadcastIsIgnored() {
        val t = BroadcastTracker(severalAllowed = true)
        assertEquals(null, t.onAdvert("B", -30, null, matches = false, nowMs = 0))
        assertEquals(null, t.chosen(0))
    }

    @Test fun theSameAddressStaysFollowedWhenALevelMovesAStep() {
        val t = BroadcastTracker(severalAllowed = true)
        t.onAdvert("A", -50, null, matches = true, nowMs = 0)
        assertEquals(-48, t.onAdvert("A", -48, null, matches = false, nowMs = 500)!!.rssi)    // a bud charging: 70 -> 71
    }

    @Test fun bothBudsBroadcastingGiveTheNearerOne() {
        val t = BroadcastTracker(severalAllowed = true)
        t.onAdvert("L", -60, null, matches = true, nowMs = 0)
        t.onAdvert("R", -42, null, matches = true, nowMs = 100)
        assertEquals("R", t.chosen(200))
    }

    @Test fun twoLooseMatchesAreNoAnswer() {
        val t = BroadcastTracker(severalAllowed = false)
        t.onAdvert("X", -60, null, matches = true, nowMs = 0)
        t.onAdvert("Y", -42, null, matches = true, nowMs = 100)
        assertEquals(null, t.chosen(200))
    }

    @Test fun aRotatedAddressTakesOver() {
        val t = BroadcastTracker(severalAllowed = false, freshMs = 3_000)
        t.onAdvert("OLD", -50, null, matches = true, nowMs = 0)
        assertEquals("NEW", t.onAdvert("NEW", -52, null, matches = true, nowMs = 5_000)!!.address)
    }

    @Test fun aSilentBroadcastGoesStale() {
        val t = BroadcastTracker(severalAllowed = true, freshMs = 3_000)
        t.onAdvert("A", -50, null, matches = true, nowMs = 0)
        assertEquals(null, t.chosen(4_000))
    }
}
