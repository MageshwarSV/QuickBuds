package com.spizganed.quickbuds.bluetooth.battery.brands

import com.spizganed.quickbuds.bluetooth.FastPairBattery

/**
 * Qualcomm GAIA v1/v2 frames under the vendor id 0x000A, as SoundPEATS ([SoundPeats]) and EarFun ([EarFun]) earbuds
 * use them for battery reads. Each brand has its own command numbers: this object only builds and reads frames.
 *
 *     FF  version  flags  length  vendor u16 BE  command u16 BE  payload[length]
 *
 * A request has version 0x04, flags 0x00 (no checksum) and no payload. A reply has version 0x03 (SoundPEATS H3) or
 * 0x04, flags 0x00, and the request command with bit 15 set (0x0306 answers 0x8306). A battery reply has two payload
 * bytes: a status (0x00: success) and the level 0-100. Sources: OpenPeats PROTOCOL.md §2 and §7, Gadgetbridge
 * EarFunPacket.java (URLs in [SoundPeats] and [EarFun]).
 */
internal object GaiaVendorFrames {
    const val VENDOR = 0x000A
    private const val SOF = 0xFF
    private const val HEADER = 8
    private const val ACK = 0x8000
    /** Reply versions in the sources: 0x03 (OpenPeats, on the wire) and 0x04 (Gadgetbridge accepts both). */
    private val VERSIONS = setOf(0x03, 0x04)

    class Frame(val vendor: Int, val command: Int, val payload: ByteArray)

    /** A read of [command], without payload: `FF 04 00 00 00 0A cmd cmd`. */
    fun request(command: Int): ByteArray =
        byteArrayOf(0xFF.toByte(), 0x04, 0x00, 0x00, 0x00, 0x0A, (command shr 8).toByte(), command.toByte())

    /**
     * The complete frames in [received], in order. A byte that does not start a valid header is skipped (noise or a
     * frame of another protocol). Stops at a frame not complete yet.
     */
    fun frames(received: ByteArray): List<Frame> {
        val out = ArrayList<Frame>()
        var i = 0
        while (i < received.size) {
            if (u(received, i) != SOF) { i++; continue }
            if (i + HEADER > received.size) break
            if (u(received, i + 1) !in VERSIONS || u(received, i + 2) != 0) { i++; continue }
            val length = u(received, i + 3)
            if (i + HEADER + length > received.size) break
            val vendor = (u(received, i + 4) shl 8) or u(received, i + 5)
            val command = (u(received, i + 6) shl 8) or u(received, i + 7)
            out += Frame(vendor, command, received.copyOfRange(i + HEADER, i + HEADER + length))
            i += HEADER + length
        }
        return out
    }

    /**
     * The levels from the battery replies in [received]: [left], [right], [case] are the read commands. Null until
     * left and right both answered. Each brand reads the case first, and the earbuds answer in order. So when left
     * and right have answered, a case reply has come already or does not come: the case then stays -1. [zeroUnknown]: the brand sends 0 for a part
     * it cannot read. No charging flag in these replies.
     */
    fun levels(received: ByteArray, left: Int, right: Int, case: Int, zeroUnknown: Boolean): FastPairBattery.Levels? {
        var l: Int? = null
        var r: Int? = null
        var c: Int? = null
        for (f in frames(received)) {
            if (f.vendor != VENDOR) continue
            val level = level(f.payload, zeroUnknown)
            when (f.command) {
                left or ACK -> l = level
                right or ACK -> r = level
                case or ACK -> c = level
            }
        }
        if (l == null || r == null) return null
        return FastPairBattery.Levels(l, r, c ?: -1, false, false, false).takeIf { it.any }
    }

    /** Status 0x00 then a level 0-100; anything else (an error status, a level above 100) is -1. */
    private fun level(payload: ByteArray, zeroUnknown: Boolean): Int {
        if (payload.size != 2 || payload[0].toInt() != 0) return -1
        val v = u(payload, 1)
        return if (v > 100 || (zeroUnknown && v == 0)) -1 else v
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
}
