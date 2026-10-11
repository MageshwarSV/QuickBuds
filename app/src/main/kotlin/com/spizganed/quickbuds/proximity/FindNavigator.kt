package com.spizganed.quickbuds.proximity

/**
 * The 1 m level of a Bluetooth LE broadcast, in dBm, before any calibration: a broadcast that states its transmit
 * power (the Tx Power Level field, which Fast Pair broadcasts carry) is about [PATH_LOSS_AT_1M_DB] dB weaker 1 m
 * away; one that does not is taken at [DEFAULT_AT_ONE_METRE_DBM]. It sets the proximity thresholds
 * ([FindThresholds]); it never gives metres on its own ([FindCalibration] does).
 */
object BroadcastDistance {
    /** Free-space loss over 1 m at 2.4 GHz. */
    const val PATH_LOSS_AT_1M_DB = 41.0
    const val DEFAULT_AT_ONE_METRE_DBM = -59.0

    fun atOneMetre(txPowerDbm: Int?): Double =
        if (txPowerDbm == null || txPowerDbm < -100 || txPowerDbm > 20) DEFAULT_AT_ONE_METRE_DBM else txPowerDbm - PATH_LOSS_AT_1M_DB

}

/**
 * Which Fast Pair broadcast is the device being searched for. A broadcast does not say whose it is; the battery
 * levels in it do: they must agree with what the device itself reports over its own link.
 */
object BroadcastMatch {

    /**
     * The supported buds: [left], [right], [case] from their own link (-1 unknown). Every part both report must
     * agree within [tolerance]; at least one part must be compared.
     */
    fun buds(advert: com.spizganed.quickbuds.bluetooth.FastPairBattery.Levels, left: Int, right: Int, case: Int, tolerance: Int = 2): Boolean {
        val pairs = listOf(advert.left to left, advert.right to right, advert.case to case).filter { it.first >= 0 && it.second > 0 }
        return pairs.isNotEmpty() && pairs.all { kotlin.math.abs(it.first - it.second) <= tolerance }
    }

    /** Another brand's earbuds, one standard [level] for the pair: a bud in the broadcast within [tolerance] of it. */
    fun single(advert: com.spizganed.quickbuds.bluetooth.FastPairBattery.Levels, level: Int, tolerance: Int = 10): Boolean =
        level > 0 && listOf(advert.left, advert.right).any { it >= 0 && kotlin.math.abs(it - level) <= tolerance }
}

/**
 * Follows the Fast Pair broadcast of the device being searched for, among all the broadcasts heard. Pure: the
 * caller says, for each broadcast, whether its battery levels match the device ([BroadcastMatch]).
 *
 *  - The device's address is learned from a matching broadcast and then followed as it is, even when a later one
 *    reads a level off by a step (a bud charging): the address is the identity until it rotates.
 *  - Earbuds rotate their broadcast address every few minutes: a new matching address takes over.
 *  - [severalAllowed]: both buds of one pair can broadcast the same levels from two addresses; the stronger one is
 *    the nearer bud. Without it (a looser, one-level match for another brand), two matching addresses are no answer.
 */
class BroadcastTracker(
    private val severalAllowed: Boolean,
    /** A broadcast this old no longer tells where the device is. */
    private val freshMs: Long = 3_000
) {

    /** The device's latest broadcast: its RSSI in dBm, the level 1 m away ([BroadcastDistance.atOneMetre]). */
    data class Reading(val address: String, val rssi: Int, val atOneMetreDbm: Double, val atMs: Long)

    private class Heard(var rssi: Int, var atOneMetre: Double, var atMs: Long, var matched: Boolean)

    private val heard = HashMap<String, Heard>()

    /** One broadcast. Returns the device's reading when this broadcast is the device's, else null. */
    fun onAdvert(address: String, rssi: Int, txPowerDbm: Int?, matches: Boolean, nowMs: Long): Reading? {
        val h = heard.getOrPut(address) { Heard(rssi, 0.0, nowMs, false) }
        h.rssi = rssi
        h.atOneMetre = BroadcastDistance.atOneMetre(txPowerDbm)
        h.atMs = nowMs
        if (matches) h.matched = true
        prune(nowMs)
        val chosen = chosen(nowMs) ?: return null
        return if (chosen == address) Reading(address, rssi, h.atOneMetre, nowMs) else null
    }

    /** The device's address now, or null: none fresh, or (without [severalAllowed]) more than one. */
    fun chosen(nowMs: Long): String? {
        val fresh = heard.filter { it.value.matched && nowMs - it.value.atMs <= freshMs }
        if (fresh.isEmpty()) return null
        if (fresh.size > 1 && !severalAllowed) return null
        return fresh.maxBy { it.value.rssi }.key
    }

    fun clear() = heard.clear()

    private fun prune(nowMs: Long) {
        heard.entries.removeAll { nowMs - it.value.atMs > freshMs * 4 }
    }
}
