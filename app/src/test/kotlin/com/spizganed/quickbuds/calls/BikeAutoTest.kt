package com.spizganed.quickbuds.calls

import com.spizganed.quickbuds.calls.SpeedRule.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeAutoTest {

    /** One sample every [everyMs] for [ms], at [kmh], ending at [end]. */
    private fun samples(kmh: Float, ms: Long, end: Long, everyMs: Long = 2_000L) =
        (0..ms / everyMs).map { Sample(end - ms + it * everyMs, kmh) }

    @Test fun ridingAMinuteFast() {
        assertTrue(SpeedRule.riding(samples(35f, 60_000L, 100_000L), 100_000L))
    }

    @Test fun notRidingTooShortOrTooSlow() {
        assertFalse(SpeedRule.riding(samples(35f, 30_000L, 100_000L), 100_000L))
        assertFalse(SpeedRule.riding(samples(25f, 60_000L, 100_000L), 100_000L))
    }

    @Test fun aTrafficLightDoesNotResetIt() {
        val s = samples(35f, 60_000L, 100_000L).mapIndexed { i, x -> if (i % 10 == 0) x.copy(kmh = 5f) else x }
        assertTrue(SpeedRule.riding(s, 100_000L))
    }

    @Test fun aGpsJumpIsNotRiding() {
        assertFalse(SpeedRule.riding(samples(400f, 60_000L, 100_000L), 100_000L))
    }

    @Test fun stoppedAfterTenSlowMinutes() {
        val end = 2_000_000L
        assertTrue(SpeedRule.stopped(samples(3f, SpeedRule.OFF_MS, end, 15_000L), end, end - SpeedRule.OFF_MS))
        // Fast within the ten minutes: not stopped.
        assertFalse(SpeedRule.stopped(samples(3f, SpeedRule.OFF_MS, end, 15_000L) + Sample(end - 60_000L, 30f), end, end - SpeedRule.OFF_MS))
        // Riding seen two minutes ago: not ten minutes yet.
        assertFalse(SpeedRule.stopped(samples(3f, SpeedRule.OFF_MS, end, 15_000L), end, end - 120_000L))
        // No fix for ten minutes (parked indoors): stopped.
        assertTrue(SpeedRule.stopped(emptyList(), end, end - SpeedRule.OFF_MS))
    }

    @Test fun speedFromTwoFixes() {
        assertEquals(36f, SpeedRule.kmh(300f, 30_000L), 0.01f)
    }
}
