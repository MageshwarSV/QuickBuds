package com.spizganed.quickbuds.bluetooth.battery.brands

import com.spizganed.quickbuds.bluetooth.FastPairBattery
import com.spizganed.quickbuds.bluetooth.battery.BrandProtocol
import java.util.UUID

/**
 * Nothing and CMF earbuds, over their control service. Frames, little-endian:
 *
 *     55  control u16  command u16  length u16  sequence u8  payload[length]  CRC-16/MODBUS u16 (control bit 5 set)
 *
 * Battery read: command 0xC007, no payload. Reply 0xC007, and the push 0xE001 on a change: payload count N, then N
 * pairs (id, status): id 02 left, 03 right, 04 case; status bit 7 charging, bits 0-6 the level.
 * Sources (facts): Gadgetbridge nothing/Ear1Support.java (control 0x5120, encodeBatteryStatusReq) and
 * krisgfx9/Wireless-Device-Battery-Monitor src/drivers/nothing_ear_protocol.h (control 0x0160, CRC-16/MODBUS: init
 * 0xFFFF, reflected polynomial 0xA001, over the header and the payload). The two sources send the read with a
 * different control value: both are sent; the earbuds answer the one they take.
 */
object Nothing : BrandProtocol {
    override val id = "nothing"
    override val status = BrandProtocol.Status.EXPERIMENTAL
    override val uuid: UUID = UUID.fromString("aeac4a03-dff5-498f-843a-34487cf133eb")

    private const val SOF = 0x55
    private const val CRC_FLAG = 0x20
    private const val READ = 0xC007
    private const val PUSH = 0xE001
    private const val HEADER = 8

    override fun requests() = listOf(read(0x5120), read(0x0160))

    /** The battery read with [control]: `55 c c 07 C0 00 00 00` and the CRC. */
    fun read(control: Int): ByteArray {
        val head = byteArrayOf(SOF.toByte(), control.toByte(), (control shr 8).toByte(),
            READ.toByte(), (READ shr 8).toByte(), 0, 0, 0)
        val crc = crc16(head, head.size)
        return head + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
    }

    override fun parse(received: ByteArray): FastPairBattery.Levels? {
        var found: FastPairBattery.Levels? = null
        var i = 0
        while (i + HEADER <= received.size) {
            if (u(received, i) != SOF) { i++; continue }
            val control = u16(received, i + 1)
            val command = u16(received, i + 3)
            val length = u16(received, i + 5)
            val crcLength = if (control and CRC_FLAG != 0) 2 else 0
            if (length > MAX_LENGTH) { i++; continue }
            val total = HEADER + length + crcLength
            if (i + total > received.size) break
            if (crcLength == 2 && crc16(received.copyOfRange(i, i + HEADER + length), HEADER + length) != u16(received, i + HEADER + length)) {
                i++; continue
            }
            if (command == READ || command == PUSH) {
                battery(received.copyOfRange(i + HEADER, i + HEADER + length))?.let { found = it }
            }
            i += total
        }
        return found
    }

    /** count N, then N pairs (id, status). */
    private fun battery(p: ByteArray): FastPairBattery.Levels? {
        if (p.isEmpty()) return null
        val n = u(p, 0)
        if (1 + n * 2 > p.size) return null
        var l = -1; var r = -1; var c = -1
        var lc = false; var rc = false; var cc = false
        for (k in 0 until n) {
            val id = u(p, 1 + k * 2)
            val st = u(p, 2 + k * 2)
            val level = st and 0x7F
            if (level > 100) continue
            val charging = st and 0x80 != 0
            when (id) {
                2 -> { l = level; lc = charging }
                3 -> { r = level; rc = charging }
                4 -> { c = level; cc = charging }
            }
        }
        return FastPairBattery.Levels(l, r, c, lc, rc, cc).takeIf { it.any }
    }

    /** CRC-16/MODBUS over the first [n] bytes. */
    fun crc16(b: ByteArray, n: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until n) {
            crc = crc xor u(b, i)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u(b, i) or (u(b, i + 1) shl 8)
    private const val MAX_LENGTH = 512
}
