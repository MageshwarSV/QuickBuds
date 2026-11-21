package com.spizganed.quickbuds.bluetooth.battery.brands

import com.spizganed.quickbuds.bluetooth.battery.BrandProtocols
import com.spizganed.quickbuds.bluetooth.battery.FastPairStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class BrandProtocolsTest {

    private fun bytes(hex: String) = hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // --- Which protocol for which earbuds ---

    @Test fun theGenericSppServiceNeedsTheBrandName() {
        assertNull(BrandProtocols.find("Rockerz 255", setOf(spp)).takeIf { it !== null && it.id.startsWith("sound") })
        assertEquals(SoundPeats, BrandProtocols.find("SOUNDPEATS Air4 Pro", setOf(spp)))
        assertEquals(EarFun, BrandProtocols.find("EarFun Free Pro 3", setOf(spp)))
        assertEquals(EarFun.AirPro4, BrandProtocols.find("EarFun Air Pro 4", setOf(spp)))
        assertEquals(GalaxyBuds.Spp, BrandProtocols.find("Galaxy Buds+ (A1B2)", setOf(spp)))
        assertNull(BrandProtocols.find("JBL Wave Buds", setOf(spp)))
    }

    @Test fun aBrandsOwnServiceComesFirst() {
        assertEquals(GalaxyBuds, BrandProtocols.find("Galaxy Buds2 Pro", setOf(spp, GalaxyBuds.uuid)))
        assertEquals(Nothing, BrandProtocols.find("Nothing Ear (2)", setOf(Nothing.uuid, FastPairStream.uuid)))
        assertEquals(FastPairStream, BrandProtocols.find("Any Buds", setOf(FastPairStream.uuid)))
    }

    // --- GAIA vendor 0x000A: SoundPEATS, EarFun ---

    @Test fun soundPeatsReadsCaseLeftRight() {
        assertArrayEquals(bytes("FF 04 00 00 00 0A 03 23"), SoundPeats.requests()[0])
        assertArrayEquals(bytes("FF 04 00 00 00 0A 03 06"), SoundPeats.requests()[1])
        assertArrayEquals(bytes("FF 04 00 00 00 0A 03 07"), SoundPeats.requests()[2])
    }

    @Test fun soundPeatsRepliesFromOpenPeats() {
        // OpenPeats PROTOCOL.md "Readings", on a SoundPEATS H3.
        val all = bytes("FF 03 00 02 00 0A 83 23 00 5F  FF 03 00 02 00 0A 83 06 00 64  FF 03 00 02 00 0A 83 07 00 64")
        val l = SoundPeats.parse(all)!!
        assertEquals(listOf(100, 100, 95), listOf(l.left, l.right, l.case))
        // Right not answered yet.
        assertNull(SoundPeats.parse(all.copyOf(20)))
    }

    @Test fun earFunAsksTheCaseOnlyOnTheAirPro4() {
        assertEquals(2, EarFun.requests().size)
        assertEquals(3, EarFun.AirPro4.requests().size)
        assertArrayEquals(bytes("FF 04 00 00 00 0A 03 17"), EarFun.AirPro4.requests()[0])
        // A level of 0 is not known on EarFun.
        val l = EarFun.parse(bytes("FF 04 00 02 00 0A 83 06 00 00  FF 04 00 02 00 0A 83 07 00 55"))!!
        assertEquals(listOf(-1, 85, -1), listOf(l.left, l.right, l.case))
    }

    // --- Nothing ---

    @Test fun nothingCrcIsModbus() {
        assertEquals(0x4B37, Nothing.crc16("123456789".toByteArray(), 9))
    }

    @Test fun nothingBatteryReply() {
        // Count 3: left 80, right 75 charging, case 50.
        val body = bytes("55 20 51 07 C0 07 00 00 03 02 50 03 CB 04 32")
        val crc = Nothing.crc16(body, body.size)
        val frame = body + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
        val l = Nothing.parse(frame)!!
        assertEquals(listOf(80, 75, 50), listOf(l.left, l.right, l.case))
        assertEquals(listOf(false, true, false), listOf(l.leftCharging, l.rightCharging, l.caseCharging))
        // A bad CRC: nothing. Not complete: nothing.
        assertNull(Nothing.parse(body + byteArrayOf(0, 0)))
        assertNull(Nothing.parse(frame.copyOf(frame.size - 1)))
        // The read requests are well formed.
        Nothing.requests().forEach { r -> assertEquals(Nothing.crc16(r, 8), (r[8].toInt() and 0xFF) or ((r[9].toInt() and 0xFF) shl 8)) }
    }

    // --- Galaxy Buds ---

    @Test fun galaxyCrcIsXmodem() {
        assertEquals(0x31C3, GalaxyBudsFrames.crc16("123456789".toByteArray(), 9))
    }

    @Test fun galaxyExtendedStatus() {
        // 0x61: payload [2] left 64, [3] right 58, [7] case 33.
        val idPayload = bytes("61 00 01 40 3A 00 00 00 21 00")
        val crc = GalaxyBudsFrames.crc16(idPayload, idPayload.size)
        val size = idPayload.size + 2
        val frame = byteArrayOf(0xFD.toByte(), size.toByte(), 0) + idPayload + byteArrayOf(crc.toByte(), (crc shr 8).toByte(), 0xDD.toByte())
        val l = GalaxyBuds.parse(frame)!!
        assertEquals(listOf(64, 58, 33), listOf(l.left, l.right, l.case))
        assertNull(GalaxyBuds.parse(frame.copyOf(frame.size - 1)))
        val bad = frame.copyOf().also { it[it.size - 3] = 0 }
        assertNull(GalaxyBuds.parse(bad))
        assertEquals(0, GalaxyBuds.requests().size)
    }

    // --- boAt WuQi probe ---

    @Test fun boatSendsTheBatteryReadNeverTheReset() {
        val read = BoatWuqi.requests().single()
        assertArrayEquals(bytes("08 EE 00 00 00 01 05 0A 00 06"), read)
        assertFalse(read.contentEquals(bytes("08 EE 00 00 00 05 01 0A 00 06")))
        assertNull(BoatWuqi.parse(bytes("09 FF 00 00 01 01 05 0D 00 50 4B 00 00")))
    }
}
