package com.spizganed.quickbuds.bluetooth.battery.brands

import com.spizganed.quickbuds.bluetooth.DeviceCatalog
import com.spizganed.quickbuds.bluetooth.FastPairBattery
import com.spizganed.quickbuds.bluetooth.battery.BrandProtocol
import java.util.UUID

/**
 * Samsung Galaxy Buds. The earbuds send their status by themselves after the link: QuickBuds sends nothing.
 *
 *     Buds+ and later   FD  header u16 (bits 0-9 size, bit 13 fragment)  id  payload  CRC u16  DD
 *     Galaxy Buds       FE  type  size u8                                id  payload  CRC u16  EE
 *
 * size = id + payload + CRC. CRC-16/XMODEM (polynomial 0x1021, init 0), little-endian, over id and payload.
 * Levels (1-100; 0 or above 100: not known): 0x60 status payload[1] left, [2] right, [6] case; 0x61 extended
 * status payload[2] left, [3] right, [7] case. The first Galaxy Buds have no case level.
 * Sources (facts): krisgfx9/Wireless-Device-Battery-Monitor src/drivers/galaxy_buds_protocol.h (from
 * GalaxyBudsClient), Gadgetbridge galaxy_buds/GalaxyBudsProtocol.java (service UUIDs, battery offsets).
 */
object GalaxyBuds : BrandProtocol {
    override val id = "galaxy-buds"
    override val status = BrandProtocol.Status.EXPERIMENTAL
    /** Galaxy Buds2 and later. */
    override val uuid: UUID = UUID.fromString("2e73a4ad-332d-41fc-90e2-16bef06523f2")

    override fun requests(): List<ByteArray> = emptyList()

    override fun parse(received: ByteArray): FastPairBattery.Levels? = GalaxyBudsFrames.levels(received, legacy = false)

    /** Galaxy Buds+ and Buds Live: the generic SPP service, so the name must be Samsung's too (Gadgetbridge). */
    object Spp : BrandProtocol by GalaxyBuds {
        override val id = "galaxy-buds-spp"
        override val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        override fun matches(name: String, uuids: Set<UUID>) =
            DeviceCatalog.brandOf(name)?.name == "Samsung" && GalaxyBuds.uuid !in uuids
    }

    /** The first Galaxy Buds (2019): their own service and the FE … EE frames (Gadgetbridge). */
    object Legacy : BrandProtocol by GalaxyBuds {
        override val id = "galaxy-buds-2019"
        override val uuid: UUID = UUID.fromString("00001102-0000-1000-8000-00805f9b34fd")
        override fun parse(received: ByteArray) = GalaxyBudsFrames.levels(received, legacy = true)
    }
}

internal object GalaxyBudsFrames {
    private const val STATUS = 0x60
    private const val EXTENDED_STATUS = 0x61

    /** The levels of the newest status frame in [received]. */
    fun levels(received: ByteArray, legacy: Boolean): FastPairBattery.Levels? {
        val som = if (legacy) 0xFE else 0xFD
        val eom = if (legacy) 0xEE else 0xDD
        var found: FastPairBattery.Levels? = null
        var i = 0
        while (i + 6 <= received.size) {
            if (u(received, i) != som) { i++; continue }
            val size = if (legacy) u(received, i + 2) else {
                val header = u(received, i + 1) or (u(received, i + 2) shl 8)
                // A fragment carries firmware data, never a status.
                if (header and 0x2000 != 0) { i++; continue }
                header and 0x3FF
            }
            if (size < 3) { i++; continue }
            if (i + 3 + size >= received.size) break
            if (u(received, i + 3 + size) != eom) { i++; continue }
            val body = received.copyOfRange(i + 3, i + 3 + size)
            val crc = u(body, size - 2) or (u(body, size - 1) shl 8)
            if (crc16(body, size - 2) != crc) { i++; continue }
            val p = body.copyOfRange(1, size - 2)
            when (u(body, 0)) {
                EXTENDED_STATUS -> if (p.size >= 4) found = levels(p, 2, 3, if (!legacy && p.size >= 8) 7 else -1)
                STATUS -> if (p.size >= 3) found = levels(p, 1, 2, if (!legacy && p.size >= 7) 6 else -1)
            }
            i += 4 + size
        }
        return found
    }

    private fun levels(p: ByteArray, l: Int, r: Int, c: Int) = FastPairBattery.Levels(
        level(u(p, l)), level(u(p, r)), if (c >= 0) level(u(p, c)) else -1, false, false, false
    ).takeIf { it.any }

    private fun level(v: Int) = if (v in 1..100) v else -1

    /** CRC-16/XMODEM over the first [n] bytes. */
    fun crc16(b: ByteArray, n: Int): Int {
        var crc = 0
        for (i in 0 until n) {
            crc = crc xor (u(b, i) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
}
