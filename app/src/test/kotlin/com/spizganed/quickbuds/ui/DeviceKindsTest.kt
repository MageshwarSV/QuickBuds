package com.spizganed.quickbuds.ui

import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.DeviceCatalog.Kind
import com.spizganed.quickbuds.notify.NotificationArt.Part
import com.spizganed.quickbuds.usage.UsageDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceKindsTest {

    @Test fun eachKindHasItsOwnRingGlyph() {
        assertEquals(Part.NECKBAND, DeviceKinds.ringPart(Kind.NECKBAND))
        assertEquals(Part.HEADPHONES, DeviceKinds.ringPart(Kind.HEADPHONES))
        assertEquals(Part.SPEAKER, DeviceKinds.ringPart(Kind.SPEAKER))
        assertEquals(Part.BOTH, DeviceKinds.ringPart(Kind.TWS))
        assertEquals(Part.BOTH, DeviceKinds.ringPart(null))
    }

    @Test fun speakersGetNoLossWarning() {
        assertEquals(R.string.features_speaker, DeviceKinds.features(Kind.SPEAKER, full = false))
        assertEquals(R.string.model_basic, DeviceKinds.features(Kind.NECKBAND, full = false))
        assertEquals(R.string.model_full, DeviceKinds.features(Kind.TWS, full = true))
    }

    @Test fun theStoredKindWinsOverTheName() {
        // Fast Pair saw a case: TWS, although "ZEB JUMBO LITE" reads as a neckband.
        assertEquals(Kind.TWS, DeviceKinds.of(UsageDevice("A", "ZEB JUMBO LITE", null, "TWS")))
        assertEquals(Kind.NECKBAND, DeviceKinds.of(UsageDevice("A", "ZEB JUMBO LITE", null, null)))
        assertEquals(Kind.NECKBAND, DeviceKinds.of(UsageDevice("A", "ZEB JUMBO LITE", null, "NOT_A_KIND")))
        assertNull(DeviceKinds.label(null))
    }
}
