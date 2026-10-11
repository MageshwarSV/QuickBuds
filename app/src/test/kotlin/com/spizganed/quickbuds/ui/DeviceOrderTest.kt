package com.spizganed.quickbuds.ui

import com.spizganed.quickbuds.bluetooth.ConnectedDevices.Entry
import com.spizganed.quickbuds.bluetooth.DeviceCatalog.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceOrderTest {

    private val buds = Entry("BU:DS", "OnePlus Nord Buds 3r", Kind.TWS, -1, null)
    private val neck = Entry("NE:CK", "ZEB JUMBO LITE", Kind.NECKBAND, 70, null)
    private val speaker = Entry("SP:KR", "JBL Flip 6", Kind.SPEAKER, 40, null)

    private fun order(plan: DeviceOrder.Plan?) = plan!!.devices.map { it.address }

    @Test fun nothingOfAnotherBrandKeepsTheHomeScreen() {
        assertNull(DeviceOrder.plan(buds, emptyList(), null, null))
        assertNull(DeviceOrder.plan(null, emptyList(), null, null))
    }

    @Test fun oneDeviceAloneHasNoStrip() {
        val p = DeviceOrder.plan(null, listOf(neck), null, null)!!
        assertEquals(listOf("NE:CK"), order(p))
        assertFalse(p.strip)
        assertFalse(p.budsControls)
    }

    @Test fun thePlayingDeviceComesFirst() {
        val p = DeviceOrder.plan(buds, listOf(neck), null, playing = "NE:CK")!!
        assertEquals(listOf("NE:CK", "BU:DS"), order(p))
        assertTrue(p.strip)
        assertFalse(p.budsControls)
    }

    @Test fun budsPlayingShowTheirOwnControls() {
        val p = DeviceOrder.plan(buds, listOf(neck), null, playing = "BU:DS")!!
        assertEquals(listOf("BU:DS", "NE:CK"), order(p))
        assertTrue(p.budsControls)
    }

    @Test fun theUsersPickBeatsThePlayingDevice() {
        val p = DeviceOrder.plan(buds, listOf(neck), picked = "BU:DS", playing = "NE:CK")!!
        assertEquals(listOf("BU:DS", "NE:CK"), order(p))
        assertTrue(p.budsControls)
    }

    @Test fun aPickThatDisconnectedIsIgnored() {
        val p = DeviceOrder.plan(buds, listOf(neck), picked = "GO:NE", playing = "NE:CK")!!
        assertEquals(listOf("NE:CK", "BU:DS"), order(p))
    }

    @Test fun withNothingPlayingTheBudsComeFirst() {
        assertEquals(listOf("BU:DS", "NE:CK"), order(DeviceOrder.plan(buds, listOf(neck), null, null)))
        // Audio on the phone speaker or a car: not one of ours.
        assertEquals(listOf("BU:DS", "NE:CK"), order(DeviceOrder.plan(buds, listOf(neck), null, "CA:R0")))
    }

    @Test fun withoutBudsTheFirstToConnectComesFirst() =
        assertEquals(listOf("NE:CK", "SP:KR"), order(DeviceOrder.plan(null, listOf(neck, speaker), null, null)))

    @Test fun theRestKeepTheirConnectionOrder() =
        assertEquals(listOf("SP:KR", "BU:DS", "NE:CK"), order(DeviceOrder.plan(buds, listOf(neck, speaker), "SP:KR", null)))

    @Test fun theBudsAreNeverListedTwice() =
        assertEquals(listOf("BU:DS", "NE:CK"), order(DeviceOrder.plan(buds, listOf(buds, neck), null, null)))
}
