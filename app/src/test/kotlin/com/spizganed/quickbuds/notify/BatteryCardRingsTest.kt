package com.spizganed.quickbuds.notify

import com.spizganed.quickbuds.notify.BatteryCard.Rings
import com.spizganed.quickbuds.widget.WidgetStateStore.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Wear codes: 4 in case, 1/5 out, 3/7 in ear, 0 off (Nord Buds 3r: in the case), -1 not reported. */
class BatteryCardRingsTest {

    private fun state(left: Int, right: Int, l: Int = 80, r: Int = 70, case: Int = 50, connected: Boolean = true) =
        State(leftStatus = left, rightStatus = right, leftBattery = l, rightBattery = r, caseBattery = case, connected = connected)

    @Test fun bothInEarShowBothAndTheCase() = assertEquals(Rings(true, true, true), BatteryCard.rings(state(3, 7)))

    @Test fun oneOutShowsOnlyThatOne() {
        assertEquals(Rings(true, true, false), BatteryCard.rings(state(3, 4)))
        assertEquals(Rings(false, true, true), BatteryCard.rings(state(4, 1)))
    }

    @Test fun outButNotWornCountsAsOut() = assertEquals(Rings(true, true, true), BatteryCard.rings(state(1, 5)))

    @Test fun nordBuds3rReportZeroForABudInTheCase() = assertEquals(Rings(false, true, true), BatteryCard.rings(state(0, 3)))

    @Test fun bothInTheCaseShowsNoCard() {
        assertNull(BatteryCard.rings(state(4, 4)))
        assertNull(BatteryCard.rings(state(0, 0)))
    }

    @Test fun disconnectedShowsNoCard() = assertNull(BatteryCard.rings(state(3, 3, connected = false)))

    @Test fun aCaseWithoutALevelIsLeftOut() = assertEquals(Rings(true, false, true), BatteryCard.rings(state(3, 3, case = 0)))

    @Test fun withoutWearReportsABudWithALevelIsOut() {
        assertEquals(Rings(true, true, true), BatteryCard.rings(state(-1, -1)))
        assertEquals(Rings(true, true, false), BatteryCard.rings(state(-1, -1, r = 0)))
        assertNull(BatteryCard.rings(state(-1, -1, l = 0, r = -1)))
    }
}
