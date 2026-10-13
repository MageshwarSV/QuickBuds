package com.spizganed.quickbuds.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudsDeviceTest {

    private val models = setOf("OnePlus Nord Buds 3r", "realme Buds Wireless 3", "OPPO Enco Air3")

    @Test fun aModelNameIsSupportedBuds() {
        assertTrue(BudsDevice.decide("OnePlus Nord Buds 3r", offersSpp = false, modelNames = models).buds)
    }

    @Test fun anotherBrandWithTheSameChipIsNot() {
        // 2026-10-13: a boAt Airdopes 800 offers the SPP UUID and was taken for realme Buds Wireless 3.
        val v = BudsDevice.decide("Airdopes 800", offersSpp = true, modelNames = models)
        assertFalse(v.buds)
        assertTrue(v.reason, v.reason.startsWith("boAt"))
        assertFalse(BudsDevice.decide("JBL WAVE BUDS", offersSpp = true, modelNames = models).buds)
    }

    @Test fun aRenamedPairWithTheUuidStillCounts() {
        assertTrue(BudsDevice.decide("My buds", offersSpp = true, modelNames = models).buds)
    }

    @Test fun aSupportedBrandNotInTheListWithTheUuidCounts() {
        assertTrue(BudsDevice.decide("realme Buds Air 9", offersSpp = true, modelNames = models).buds)
        assertTrue(BudsDevice.decide("OnePlus Buds 9", offersSpp = true, modelNames = models).buds)
    }

    @Test fun noNameMatchAndNoUuidIsNot() {
        assertFalse(BudsDevice.decide("Xech Speakerpods 3.0", offersSpp = false, modelNames = models).buds)
        assertFalse(BudsDevice.decide(null, offersSpp = false, modelNames = models).buds)
    }
}
