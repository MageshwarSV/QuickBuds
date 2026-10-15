package com.spizganed.quickbuds.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryDrainTest {

    private val min = 60_000L

    /** Level reports at minutes: each pair is (minute, level), all while listening. */
    private fun listen(vararg reports: Pair<Int, Int>, from: DrainState = DrainState()) =
        reports.fold(from) { s, (m, level) -> BatteryDrain.onLevel(s, m * min, level, draining = true) }

    @Test fun measuredFromTheFirstDropToTheLast() {
        // Connected at 95% (somewhere inside a step): 90% at 10 min, 60% at 100 min.
        val s = BatteryDrain.onDisconnected(listen(0 to 95, 10 to 90, 40 to 80, 70 to 70, 100 to 60), 101 * min)
        assertEquals(listOf(DrainRun(100 * min, 30, 90 * min)), s.runs)
        val r = BatteryDrain.rate(s, 101 * min)!!
        assertEquals(20.0, r.pctPerHour, 0.001)
        assertEquals(90 * min, r.listenedMs)
    }

    @Test fun howLongALevelLasts() {
        val r = BatteryDrain.Rate(pctPerHour = 20.0, listenedMs = 90 * min)
        assertEquals(3 * 60 * min, BatteryDrain.lastsMs(60, r))   // 60% at 20%/h: 3 h
        assertEquals(5 * 60 * min, BatteryDrain.lastsMs(100, r))  // a full charge: 5 h
    }

    @Test fun notEnoughListeningGivesNoEstimate() {
        val s = BatteryDrain.onDisconnected(listen(0 to 100, 5 to 90, 20 to 80), 21 * min)   // 15 min measured
        assertNull(BatteryDrain.rate(s, 21 * min))
    }

    @Test fun theOpenStretchCountsBeforeTheDisconnect() {
        val s = listen(0 to 100, 10 to 90, 46 to 80, 82 to 70)   // still listening: 20% in 72 min
        assertEquals(1_200.0 / 72, BatteryDrain.rate(s, 82 * min)!!.pctPerHour, 0.001)
    }

    @Test fun chargingEndsTheStretch() {
        var s = listen(0 to 100, 10 to 90, 46 to 80)
        s = BatteryDrain.onLevel(s, 50 * min, 80, draining = false)   // into the case
        assertEquals(listOf(DrainRun(46 * min, 10, 36 * min)), s.runs)
        s = listen(200 to 100, 210 to 90, from = s)                   // charged, a new stretch
        assertEquals(1, s.runs.size)
    }

    @Test fun aLevelThatGoesUpEndsTheStretch() {
        val s = listen(0 to 80, 10 to 70, 46 to 60, 60 to 90)   // charged elsewhere, reconnected higher
        assertEquals(listOf(DrainRun(46 * min, 10, 36 * min)), s.runs)
    }

    @Test fun aQuickCorrectionIsNoise() {
        val s = BatteryDrain.onDisconnected(listen(0 to 100, 1 to 80, 2 to 70), 3 * min)   // 10% in 1 min
        assertEquals(emptyList<DrainRun>(), s.runs)
    }

    @Test fun oldRunsAgeOut() {
        val old = DrainState(runs = listOf(DrainRun(0L, 30, 90 * min)))
        assertNull(BatteryDrain.rate(old, BatteryDrain.WINDOW_MS + min))
    }

    @Test fun theBudThatRunsOutFirstCounts() {
        assertEquals(60, BatteryDrain.budsLevel(70, 60, leftInUse = true, rightInUse = true))
        assertEquals(70, BatteryDrain.budsLevel(70, 60, leftInUse = true, rightInUse = false))   // right in the case
        assertNull(BatteryDrain.budsLevel(70, 60, leftInUse = false, rightInUse = false))
        assertEquals(70, BatteryDrain.budsLevel(70, -1, leftInUse = true, rightInUse = true))
    }
}
