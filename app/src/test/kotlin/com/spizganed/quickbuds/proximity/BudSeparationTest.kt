package com.spizganed.quickbuds.proximity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudSeparationTest {

    @Test fun aBudOutOfTheCaseThatGoesIsLost() {
        // In ear (3), then disconnected (0), the other one still in an ear.
        assertTrue(BudSeparation.lost(prev = 3, now = 0, other = 3, level = 60))
        // Out of ear (1) on a table, the other one in a pocket (5).
        assertTrue(BudSeparation.lost(prev = 1, now = 0, other = 5, level = -1))
    }

    @Test fun aBudInAClosedCaseIsNotLost() {
        assertFalse(BudSeparation.lost(prev = 4, now = 0, other = 3, level = 60))
    }

    @Test fun aClosingLidTakesBoth() {
        assertFalse(BudSeparation.lost(prev = 3, now = 0, other = 0, level = 60))
    }

    @Test fun notSeenBeforeIsNotLost() {
        assertFalse(BudSeparation.lost(prev = -1, now = 0, other = 3, level = 60))
    }

    @Test fun aBudPutIntoTheCaseIsNotLeftBehind() {
        val since = 100_000L
        // Out of ear (1) straight to 0 is what docking looks like too: one more sign decides.
        assertTrue(BudSeparation.intoCase(since, caseOpenAtMs = since - 3_000L, charging = false, caseLevelAtMs = 0L))
        assertTrue(BudSeparation.intoCase(since, caseOpenAtMs = 0L, charging = true, caseLevelAtMs = 0L))
        assertTrue(BudSeparation.intoCase(since, caseOpenAtMs = 0L, charging = false, caseLevelAtMs = since + 2_000L))
    }

    @Test fun noSignOfTheCaseIsLeftBehind() {
        val since = 100_000L
        // The case opened long before (this morning), no charging, the case level from before.
        assertFalse(BudSeparation.intoCase(since, caseOpenAtMs = since - BudSeparation.LOOKBACK_MS - 1, charging = false,
            caseLevelAtMs = since - 60_000L))
        assertFalse(BudSeparation.intoCase(since, caseOpenAtMs = 0L, charging = false, caseLevelAtMs = 0L))
    }

    @Test fun anEmptyBudIsNotLost() {
        assertFalse(BudSeparation.lost(prev = 3, now = 0, other = 3, level = BudSeparation.EMPTY_PCT))
    }
}
