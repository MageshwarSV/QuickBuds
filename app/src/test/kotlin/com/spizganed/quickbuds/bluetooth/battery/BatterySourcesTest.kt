package com.spizganed.quickbuds.bluetooth.battery

import com.spizganed.quickbuds.bluetooth.FastPairBattery.Levels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatterySourcesTest {

    private val now = 10_000_000L
    private fun lv(l: Int, r: Int, c: Int) = Levels(l, r, c, false, false, false)
    private fun r(levels: Levels, source: BatterySource, ageMs: Long = 0L) = SourceReading(levels, source, now - ageMs)

    @Test fun theMostExactFreshSourceWins() {
        val advert = r(lv(80, 80, 60), BatterySource.FAST_PAIR_ADVERT)
        val brand = r(lv(83, 76, 54), BatterySource.BRAND, ageMs = 60_000L)
        assertEquals(brand, BatteryResolver.pick(listOf(advert, brand), now, 80))
    }

    @Test fun aStaleOwnReportGivesWayToTheBroadcast() {
        val advert = r(lv(80, 80, 60), BatterySource.FAST_PAIR_ADVERT)
        val brand = r(lv(83, 76, 54), BatterySource.BRAND, ageMs = BatteryResolver.ASKED_STALE_MS + 1)
        assertEquals(advert, BatteryResolver.pick(listOf(advert, brand), now, 80))
    }

    @Test fun levelsFarFromTheStandardOneAreNotTrusted() {
        val brand = r(lv(20, 15, 50), BatterySource.BRAND)
        assertNull(BatteryResolver.pick(listOf(brand), now, 80))
        // No standard level: nothing to check against.
        assertEquals(brand, BatteryResolver.pick(listOf(brand), now, -1))
        // One bud close is enough (the standard level is often one bud's).
        val one = r(lv(78, 20, 50), BatterySource.BRAND)
        assertEquals(one, BatteryResolver.pick(listOf(one), now, 80))
    }

    @Test fun nothingKnownIsNoReading() {
        assertNull(BatteryResolver.pick(listOf(r(lv(-1, -1, -1), BatterySource.BRAND)), now, 80))
        assertNull(BatteryResolver.pick(emptyList(), now, 80))
    }

    @Test fun theFastPairStreamBatteryMessage() {
        // Google's example: L 87%, R 65%, case unknown.
        val l = FastPairStream.parse(bytes("03 03 00 03 57 41 7F"))!!
        assertEquals(listOf(87, 65, -1), listOf(l.left, l.right, l.case))
        // Other messages first (model id 03 01), then a charging case.
        val m = FastPairStream.parse(bytes("03 01 00 03 AA BB CC 03 03 00 03 50 4B E4"))!!
        assertEquals(listOf(80, 75, 100), listOf(m.left, m.right, m.case))
        assertEquals(true, m.caseCharging)
        // Not complete yet: nothing.
        assertNull(FastPairStream.parse(bytes("03 03 00 03 57")))
        // Not this framing.
        assertNull(FastPairStream.parse(bytes("FF FF FF FF 00")))
    }

    @Test fun theAirPodsAdvertisement() {
        // Status bit 5 set: low nibble of byte 6 is the left pod. Left 70%, right 100%, case 50%, case charging.
        val a = AppleAdvert.parse(bytes("07 19 01 0E 20 2B A7 45 00 00 00"))!!
        assertEquals(listOf(70, 100, 50), listOf(a.left, a.right, a.case))
        assertEquals(listOf(false, false, true), listOf(a.leftCharging, a.rightCharging, a.caseCharging))
        // Bit 5 clear: the pods swap, so do their charging bits. Right pod out of the case (F).
        val b = AppleAdvert.parse(bytes("07 19 01 0E 20 0B 7F 1F 00 00 00"))!!
        assertEquals(listOf(70, -1, -1), listOf(b.left, b.right, b.case))
        assertEquals(false, b.leftCharging)
        // Another Apple message type: not this.
        assertNull(AppleAdvert.parse(bytes("10 05 01 18 00 00 00 00")))
    }

    private fun bytes(hex: String) = hex.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
