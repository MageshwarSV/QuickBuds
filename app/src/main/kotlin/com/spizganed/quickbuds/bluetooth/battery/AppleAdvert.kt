package com.spizganed.quickbuds.bluetooth.battery

import com.spizganed.quickbuds.bluetooth.FastPairBattery

/**
 * Left, right and case from the AirPods and Beats "proximity pairing" advertisement: manufacturer data of Apple
 * (company 0x004C), type 0x07, length 0x19. The open part of it, as OpenPods reads it (GPL-3.0, PodsStatus.java):
 *
 *     [0] 07  [1] 19  [2] prefix  [3..4] model  [5] status  [6] two pod levels  [7] charging bits | case level
 *
 * Levels are nibbles: 0-10 tens of percent (11-14 also full), 15 not there. Byte 5 bit 5 set: the low nibble of
 * byte 6 is the left pod; clear: the high nibble is (the charging bits 0 and 1 swap the same way). Charging:
 * byte 7 high nibble, bit 2 the case. Bytes 11-26 are encrypted: not read. Pure.
 */
object AppleAdvert {
    const val COMPANY = 0x004C
    const val TYPE = 0x07
    const val LENGTH = 0x19

    /** The levels in [data] (manufacturer data without the company id), or null when it is not this message. */
    fun parse(data: ByteArray?): FastPairBattery.Levels? {
        if (data == null || data.size < 8) return null
        if ((data[0].toInt() and 0xFF) != TYPE || (data[1].toInt() and 0xFF) != LENGTH) return null
        val status = data[5].toInt() and 0xFF
        // OpenPods: flipped when bit 1 of the status byte's high nibble is clear.
        val flipped = (status and 0x20) == 0
        val pods = data[6].toInt() and 0xFF
        val high = pods shr 4
        val low = pods and 0x0F
        val leftNibble = if (flipped) high else low
        val rightNibble = if (flipped) low else high
        val b7 = data[7].toInt() and 0xFF
        val charge = b7 shr 4
        val case = level(b7 and 0x0F)
        val left = level(leftNibble)
        val right = level(rightNibble)
        return FastPairBattery.Levels(
            left, right, case,
            leftCharging = left >= 0 && (charge and (if (flipped) 0b10 else 0b01)) != 0,
            rightCharging = right >= 0 && (charge and (if (flipped) 0b01 else 0b10)) != 0,
            caseCharging = case >= 0 && (charge and 0b100) != 0
        ).takeIf { it.any }
    }

    private fun level(nibble: Int) = when (nibble) {
        in 0..10 -> nibble * 10
        in 11..14 -> 100
        else -> -1
    }

    /**
     * Which broadcast is the connected AirPods' (their address is random): the strongest of the last window, if at
     * least [MIN_RSSI] dBm (OpenPods' rule: the AirPods are near the phone). Main thread.
     */
    class Picker {
        private var best: Pair<Int, FastPairBattery.Levels>? = null

        fun onAdvert(rssi: Int, levels: FastPairBattery.Levels) {
            if (rssi < MIN_RSSI) return
            if (best == null || rssi > best!!.first) best = rssi to levels
        }

        /** The window's pick, and a fresh window. */
        fun take(): FastPairBattery.Levels? = best?.second.also { best = null }
    }

    const val MIN_RSSI = -60
}
