package com.spizganed.quickbuds.bluetooth.battery

import com.spizganed.quickbuds.bluetooth.FastPairBattery
import java.util.UUID

/**
 * Google Fast Pair message stream, the "Device information" battery message. Published by Google:
 * developers.google.com/nearby/fast-pair/specifications/extensions/messagestream and …/deviceinformation.
 *
 *     group u8, code u8, length u16 big-endian, data
 *     03 03 00 03  L R C      battery updated: one byte per part, bit 7 charging, bits 0-6 level, 0x7F unknown
 *
 * The earbuds send it on connect and on each change; QuickBuds sends nothing. Any brand with Fast Pair may offer
 * the service. Google Play services opens the same channel on each connect: whether a second reader gets the
 * message is what the first tests show (logged either way).
 */
object FastPairStream : BrandProtocol {
    override val id = "fastpair-stream"
    override val status = BrandProtocol.Status.EXPERIMENTAL
    override val uuid: UUID = UUID.fromString("df21fe2c-2515-4fdb-8886-f12c4d67927c")

    override fun requests(): List<ByteArray> = emptyList()

    /** The last battery message in [received]; stops at a frame not complete yet. */
    override fun parse(received: ByteArray): FastPairBattery.Levels? {
        var i = 0
        var found: FastPairBattery.Levels? = null
        while (i + 4 <= received.size) {
            val group = received[i].toInt() and 0xFF
            val code = received[i + 1].toInt() and 0xFF
            val length = ((received[i + 2].toInt() and 0xFF) shl 8) or (received[i + 3].toInt() and 0xFF)
            // Not this framing (or garbage): nothing to trust in the rest.
            if (length > MAX_LENGTH) return found
            if (i + 4 + length > received.size) break
            if (group == 0x03 && code == 0x03 && length == 3) {
                found = levels(received[i + 4], received[i + 5], received[i + 6]) ?: found
            }
            i += 4 + length
        }
        return found
    }

    /** The same three bytes as the advertisement ([FastPairBattery.parse]). */
    fun levels(l: Byte, r: Byte, c: Byte): FastPairBattery.Levels? {
        fun level(b: Byte) = (b.toInt() and 0x7F).let { if (it > 100) -1 else it }
        fun charging(b: Byte) = (b.toInt() and 0x80) != 0 && (b.toInt() and 0x7F) <= 100
        return FastPairBattery.Levels(level(l), level(r), level(c), charging(l), charging(r), charging(c)).takeIf { it.any }
    }

    private const val MAX_LENGTH = 1024
}
