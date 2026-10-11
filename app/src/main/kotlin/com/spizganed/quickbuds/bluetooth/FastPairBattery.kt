package com.spizganed.quickbuds.bluetooth

import kotlin.math.abs

/**
 * Left, right and case battery from the Google Fast Pair advertisement (service data UUID 0xFE2C), for earbuds of
 * any brand that support Fast Pair. The format is Google's published Fast Pair specification ("Advertising when
 * not discoverable", "Battery notification"); nothing here is guessed:
 *
 *     00                      version and flags
 *     LT <L bytes>            account key filter (T 0 show UI, 2 hide UI)
 *     LT <L bytes>            salt (T 1)
 *     3T <3 bytes>            battery (T 3 show UI, 4 hide UI): left, right, case
 *
 * Each battery byte: bit 7 charging, bits 0-6 the level 0-100; 0x7F means unknown.
 *
 * The advertisement does not say WHOSE earbuds sent it: that is hidden in the account key filter, which only
 * Google Play services can test. So [Picker] trusts a broadcast only when nothing else could have sent it.
 */
object FastPairBattery {

    /**
     * Whether to listen at all: one other device connected ([trackedKinds]), earbuds or of unknown kind (a neckband,
     * headphones or a speaker has no left, right or case), no supported buds broadcasting next to it, the card on.
     */
    fun allowed(trackedKinds: List<DeviceCatalog.Kind?>, supportedBudsConnected: Boolean, cardOn: Boolean): Boolean {
        // Not singleOrNull(): an unknown kind is null too.
        if (trackedKinds.size != 1) return false
        val only = trackedKinds[0]
        return (only == null || only == DeviceCatalog.Kind.TWS) && !supportedBudsConnected && cardOn
    }

    /** Levels 0-100, -1 when the earbuds do not report that part. */
    data class Levels(
        val left: Int, val right: Int, val case: Int,
        val leftCharging: Boolean, val rightCharging: Boolean, val caseCharging: Boolean
    ) {
        val any: Boolean get() = left >= 0 || right >= 0 || case >= 0
    }

    /** The battery part of a 0xFE2C service data block, or null when it has none (or is malformed). */
    fun parse(data: ByteArray?): Levels? {
        if (data == null || data.size < 2) return null
        // Version 0 only. A 3-byte block is the discoverable form (a model id), which carries no battery.
        if ((data[0].toInt() and 0xF0) != 0) return null
        var i = 1
        while (i < data.size) {
            val header = data[i].toInt() and 0xFF
            val length = header shr 4
            val type = header and 0x0F
            if (i + 1 + length > data.size) return null
            if ((type == 3 || type == 4) && length == 3) {
                fun level(b: Byte) = (b.toInt() and 0x7F).let { if (it > 100) -1 else it }
                fun charging(b: Byte) = (b.toInt() and 0x80) != 0 && (b.toInt() and 0x7F) <= 100
                val l = data[i + 1]; val r = data[i + 2]; val c = data[i + 3]
                return Levels(level(l), level(r), level(c), charging(l), charging(r), charging(c)).takeIf { it.any }
            }
            i += 1 + length
        }
        return null
    }

    /**
     * The safeguards. A broadcast is used only when:
     *  - it is the only Fast Pair battery broadcast heard nearby in the last [windowMs] (a second set of earbuds
     *    around, someone else's, makes it ambiguous: nothing is shown);
     *  - it is strong ([minRssi] dBm or more): the connected earbuds are close to the phone;
     *  - it reports both buds: earbuds always do, a lone side is not a pair of earbuds;
     *  - the connected earbuds have reported their own standard level, and a bud's level is within [tolerance]
     *    of it. Without that level there is nothing to check the broadcast against: it is not used.
     * Otherwise [pick] returns null and the card keeps the single standard level.
     *
     * Was: the cross-check only when a standard level was known, and one side was enough. A neckband that had not
     * reported its level yet was given another device's broadcast ("R=70").
     */
    class Picker(
        private val windowMs: Long = 30_000,
        private val minRssi: Int = -75,
        private val tolerance: Int = 15
    ) {
        private class Heard(val rssi: Int, val levels: Levels, val atMs: Long)

        private val heard = HashMap<String, Heard>()

        /** One advertisement: [address] is the advertiser's (rotating) BLE address. */
        fun onAdvert(address: String, rssi: Int, levels: Levels, nowMs: Long) {
            heard[address] = Heard(rssi, levels, nowMs)
        }

        fun pick(nowMs: Long, standardLevel: Int?): Levels? {
            heard.values.removeAll { nowMs - it.atMs > windowMs }
            val only = heard.values.singleOrNull() ?: return null
            if (only.rssi < minRssi) return null
            val l = only.levels
            if (l.left < 0 || l.right < 0) return null
            if (standardLevel == null || standardLevel <= 0) return null
            if (listOf(l.left, l.right).none { abs(it - standardLevel) <= tolerance }) return null
            return l
        }

        fun clear() = heard.clear()
    }
}
