package com.spizganed.quickbuds.proximity

import com.spizganed.quickbuds.proximity.DirectionFinder.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

class DirectionFinderTest {

    /** Turning a full circle in [steps], facing [target]: the signal is [contrast] dB stronger facing it. */
    private fun sweep(f: DirectionFinder, target: Double, contrast: Double, steps: Int = 36, start: Long = 0): Long {
        var t = start
        for (i in 0 until steps) {
            val h = i * 360.0 / steps
            f.add(h, -70 + contrast * (1 + cos(Math.toRadians(h - target))) / 2, t)
            t += 400
        }
        return t
    }

    @Test fun aFullTurnFindsTheDirection() {
        for (target in listOf(0.0, 45.0, 135.0, 250.0, 330.0)) {
            val f = DirectionFinder()
            val t = sweep(f, target, contrast = 8.0)
            val got = (f.result(t) as Result.Found).bearing
            assertTrue("target $target got $got", abs(DirectionFinder.turn(got, target)) <= 20)
        }
    }

    @Test fun halfATurnGivesNoArrow() {
        val f = DirectionFinder()
        for (i in 0 until 18) f.add(i * 10.0, -62.0, i * 400L)          // 0 to 170 degrees only
        val r = f.result(18 * 400L)
        assertTrue(r is Result.Turning && r.coverage < 0.75)
    }

    @Test fun noClearDifferenceGivesNoArrow() =
        assertEquals(Result.Unclear, DirectionFinder().let { f -> f.result(sweep(f, 90.0, contrast = 1.5)) })

    @Test fun oldReadingsAgeOut() {
        val f = DirectionFinder(windowMs = 10_000)
        val t = sweep(f, 90.0, contrast = 8.0)
        assertTrue(f.result(t + 30_000) is Result.Turning)
    }

    @Test fun anotherTurnAfterWalkingFindsTheNewDirection() {
        val f = DirectionFinder(windowMs = 15_000)
        var t = sweep(f, 90.0, contrast = 8.0)
        t = sweep(f, 270.0, contrast = 8.0, start = t + 20_000)
        assertTrue(abs(DirectionFinder.turn((f.result(t) as Result.Found).bearing, 270.0)) <= 20)
    }

    @Test fun arrowTurnsTheShortWay() {
        assertEquals(20.0, DirectionFinder.turn(350.0, 10.0), 0.001)
        assertEquals(-20.0, DirectionFinder.turn(10.0, 350.0), 0.001)
    }
}
