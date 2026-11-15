package com.spizganed.quickbuds.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseChargeTest {

    private val h = 3_600_000L

    private fun snap(l: Int, r: Int, c: Int, docked: Boolean = true, caseCharging: Boolean = false, at: Long = 0L) =
        CaseSnap(l, r, c, docked, docked, caseCharging, at)

    private fun run(vararg snaps: CaseSnap) = snaps.fold(CaseState()) { s, n -> CaseCharge.onReport(s, n) }

    @Test fun budsChargedFromTheCaseAcrossALidClose() {
        // In the case at 40% and 50%, case 80%; the lid opens later: buds 100%, case 60%.
        val s = run(snap(40, 50, 80, at = 0), snap(100, 100, 60, at = h))
        assertEquals(listOf(CaseStep(h, 20, 110)), s.steps)
    }

    @Test fun aChargingCaseCountsNothing() {
        assertTrue(run(snap(40, 50, 80, at = 0), snap(100, 100, 90, caseCharging = true, at = h)).steps.isEmpty())
        // Plugged in while the lid was closed: the case level went up.
        assertTrue(run(snap(40, 50, 60, at = 0), snap(100, 100, 70, at = h)).steps.isEmpty())
    }

    @Test fun budsOutOfTheCaseCountNothing() {
        assertTrue(run(snap(40, 50, 80, docked = false, at = 0), snap(30, 40, 80, at = h)).steps.isEmpty())
    }

    @Test fun onlyTheBudInTheCaseGains() {
        val s = run(CaseSnap(40, 50, 80, leftDocked = true, rightDocked = false, caseCharging = false, atMs = 0),
            snap(90, 30, 70, at = h))
        assertEquals(listOf(CaseStep(h, 10, 50)), s.steps)
    }

    @Test fun reportsTooFarApartAreNotCompared() {
        assertTrue(run(snap(40, 50, 80, at = 0), snap(100, 100, 60, at = CaseCharge.MAX_GAP_MS + 1)).steps.isEmpty())
    }

    @Test fun anUnknownCaseLevelKeepsTheLastSnapshot() {
        val s = run(snap(40, 50, 80, at = 0), snap(60, 60, -1, at = h / 2), snap(100, 100, 60, at = h))
        assertEquals(listOf(CaseStep(h, 20, 110)), s.steps)
    }

    @Test fun theEstimate() {
        assertNull(CaseCharge.estimate(run(snap(40, 50, 80, at = 0), snap(60, 60, 75, at = h)), h))
        // 40% of case for 200% of buds: 0.2 case per bud percent, so a full case is 2.5 charges of both buds.
        val s = run(snap(0, 0, 100, at = 0), snap(100, 100, 60, at = h))
        val e = CaseCharge.estimate(s, h)!!
        assertEquals(0.2, e.casePerBudPct, 1e-9)
        assertEquals(2.5, CaseCharge.charges(e, 100), 1e-9)
        assertEquals(1.0, CaseCharge.charges(e, 40), 1e-9)
        // 40% of case gives each bud 100%.
        assertEquals(100.0, CaseCharge.budPctFrom(e, 40), 1e-9)
    }

    @Test fun oldStepsLeaveTheEstimate() {
        val s = run(snap(0, 0, 100, at = 0), snap(100, 100, 60, at = h))
        assertNull(CaseCharge.estimate(s, h + CaseCharge.WINDOW_MS + 1))
    }
}
