package com.spizganed.quickbuds.proximity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RssiFilterTest {

    private fun filter(vararg readings: Int, stepMs: Long = 1_000): RssiFilter =
        RssiFilter().apply { readings.forEachIndexed { i, r -> add(r, i * stepMs) } }

    @Test fun implausibleReadingsAreRejected() {
        val f = RssiFilter()
        assertFalse(f.add(127, 0))    // Android's "not available"
        assertFalse(f.add(-128, 0))
        assertEquals(0, f.count)
        assertTrue(f.add(-60, 0))
    }

    @Test fun nearZeroReadingsAreTheGoldenRange() = assertEquals(RssiScale.GOLDEN_RANGE, filter(0, -2, 0).scale)

    @Test fun usualDbmReadingsAreDbm() = assertEquals(RssiScale.DBM, filter(-60, -62, -58).scale)

    @Test fun aFixedScaleIsNeverDetected() {
        // A broadcast beside the phone at -25 dBm would look like golden range to the detection.
        val f = RssiFilter(fixedScale = RssiScale.DBM)
        f.add(-25, 0); f.add(-24, 1_000); f.add(-26, 2_000)
        assertEquals(RssiScale.DBM, f.scale)
        f.reset()
        assertEquals(RssiScale.DBM, f.scale)
    }

    @Test fun theScaleWaitsForThreeReadings() = assertEquals(null, filter(0, 0).scale)

    @Test fun aDbmReadingAfterGoldenSwitchesToDbm() {
        val f = filter(0, 0, 0, 0)
        f.add(-70, 10_000)
        assertEquals(RssiScale.DBM, f.scale)
        assertEquals(1, f.count)   // the golden readings are gone
    }

    @Test fun aLoneSpikeIsDropped() {
        // Captured near the phone: one -22 between zeros raised a false warning.
        val f = filter(0, 0, 0, -22, 0, 0)
        assertEquals(0.0, f.smoothed, 0.001)
    }

    @Test fun aRealDropIsFollowed() {
        val f = filter(0, 0, 0, -15, -15, -15, -15)
        assertTrue(f.smoothed < -5)
    }

    @Test fun weakeningHasANegativeSlope() = assertTrue(filter(0, -2, -4, -6, -8, -10, -12).slopePer10s(6_000) < -5)

    @Test fun steadyHasNoSlope() = assertEquals(0.0, filter(-5, -5, -5, -5, -5).slopePer10s(4_000), 0.001)

    @Test fun strengtheningHasAPositiveSlope() = assertTrue(filter(-60, -58, -56, -54, -52, -50).slopePer10s(5_000) > 0)

    @Test fun twoPointsAreNoTrend() = assertEquals(0.0, filter(-60, -70).slopePer10s(1_000), 0.0)

    @Test fun theBaselineIsTheStrongestLevel() {
        val f = filter(-2, -2, -2, -10, -10, -10, -10)
        assertEquals(-2.0, f.baseline(), 0.001)
    }

    @Test fun readyAfterTheMinimumReadings() {
        assertFalse(filter(0, 0, 0).ready)
        assertTrue(filter(0, 0, 0, 0).ready)
    }

    @Test fun resetForgetsEverything() {
        val f = filter(0, 0, 0, 0)
        f.reset()
        assertEquals(0, f.count)
        assertEquals(null, f.scale)
        assertTrue(f.baseline().isNaN())
    }
}
