package com.spizganed.quickbuds.bluetooth

import com.spizganed.quickbuds.bluetooth.DeviceCatalog.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCatalogTest {

    private fun brand(name: String) = DeviceCatalog.brandOf(name)?.name
    private fun kind(name: String) = DeviceCatalog.kindOf(name)

    @Test fun devicesPairedOnATestPhone() {
        assertEquals("boAt", brand("Airdopes 800")); assertEquals(Kind.TWS, kind("Airdopes 800"))
        assertEquals("boAt", brand("Airdopes 800 HiDef"))
        assertEquals("JBL", brand("JBL WAVE BUDS")); assertEquals(Kind.TWS, kind("JBL WAVE BUDS"))
        assertEquals("realme", brand("realme Buds T300")); assertEquals(Kind.TWS, kind("realme Buds T300"))
        assertEquals("OnePlus", brand("OnePlus Nord Buds 3r")); assertEquals(Kind.TWS, kind("OnePlus Nord Buds 3r"))
        assertEquals("Xech", brand("Xech Speakerpods 3.0")); assertEquals(Kind.TWS, kind("Xech Speakerpods 3.0"))
        assertEquals("Zebronics", brand("ZEB JUMBO LITE")); assertEquals(Kind.NECKBAND, kind("ZEB JUMBO LITE"))
    }

    @Test fun neckbandsHeadphonesAndSpeakers() {
        assertEquals(Kind.NECKBAND, kind("boAt Rockerz 255 Pro+"))
        assertEquals(Kind.HEADPHONES, kind("boAt Rockerz 450"))
        assertEquals(Kind.SPEAKER, kind("boAt Stone 350"))
        assertEquals(Kind.NECKBAND, kind("OnePlus Bullets Wireless Z2"))
        assertEquals(Kind.NECKBAND, kind("realme Buds Wireless 3"))
        assertEquals(Kind.NECKBAND, kind("WI-C200"))
        assertEquals(Kind.HEADPHONES, kind("WH-1000XM5"))
        assertEquals(Kind.TWS, kind("WF-1000XM5"))
        assertEquals(Kind.SPEAKER, kind("JBL Flip 6"))
        assertEquals(Kind.SPEAKER, kind("JBL Charge 5"))
        assertEquals(Kind.HEADPHONES, kind("JBL TUNE 510BT"))
        assertEquals(Kind.SPEAKER, kind("WONDERBOOM 4"))
        assertEquals(Kind.HEADPHONES, kind("AirPods Max"))
        assertEquals(Kind.TWS, kind("AirPods Pro"))
    }

    @Test fun genericSeriesWordsDoNotNameABrand() {
        // "Elite", "flip", "earbuds" are series words of some brands, not brands.
        assertNull(brand("Elite Earbuds"))
        assertNull(brand("Flip Speaker"))
        assertEquals("Bose", brand("Bose Noise Cancelling Headphones 700"))
        assertEquals("Crossbeats", brand("Crossbeats Torq"))
    }

    @Test fun unknownDevicesStillGetAKindFromTheirName() {
        assertNull(brand("XYZ Pods 2"))
        assertEquals(Kind.TWS, kind("XYZ Pods 2"))
        assertEquals(Kind.NECKBAND, kind("Generic Neckband"))
        assertEquals(Kind.SPEAKER, kind("Party Speaker 20W"))
        assertNull(kind("BT-5.3"))
    }

    @Test fun certaintyComesFirst() {
        assertEquals(Kind.TWS, DeviceCatalog.kindOf("ZEB JUMBO LITE", hasCase = true))
        assertEquals(Kind.SPEAKER, DeviceCatalog.kindOf("Airdopes 141", speakerClass = true))
    }

    @Test fun redmiIsNotXiaomi() {
        // Found in review: "mi buds" is inside "redmi buds".
        assertEquals("Redmi", brand("Redmi Buds 5 Pro"))
        assertEquals("Xiaomi", brand("Mi True Wireless Earphones 2"))
        assertEquals("Xiaomi", brand("Xiaomi Buds 5"))
        assertEquals(Kind.NECKBAND, kind("Redmi SonicBass"))
    }

    @Test fun jblTuneTwsAreEarbudsAndTuneBtANeckband() {
        assertEquals(Kind.TWS, kind("JBL Tune 125TWS"))
        assertEquals(Kind.TWS, kind("JBL TUNE 225TWS"))
        assertEquals(Kind.NECKBAND, kind("JBL TUNE125BT"))
        assertEquals(Kind.NECKBAND, kind("JBL Tune 215BT"))
    }

    @Test fun beatsFlexIsANeckband() = assertEquals(Kind.NECKBAND, kind("Beats Flex"))

    @Test fun everyBrandHasASeriesAndUniqueName() {
        val names = DeviceCatalog.BRANDS.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(DeviceCatalog.BRANDS.all { it.series.isNotEmpty() && it.match.isNotEmpty() })
    }
}
