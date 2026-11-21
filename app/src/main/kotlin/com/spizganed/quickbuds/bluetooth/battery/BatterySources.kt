package com.spizganed.quickbuds.bluetooth.battery

import com.spizganed.quickbuds.bluetooth.FastPairBattery
import kotlin.math.abs

/**
 * Where left, right and case levels of another brand's earbuds came from. [rank]: more is more exact. A broadcast
 * comes only with the case open and is matched by levels and signal ([FastPairBattery.Picker]); the Fast Pair
 * message stream and a brand protocol ask the device itself.
 */
enum class BatterySource(val rank: Int) {
    /** Google Fast Pair advertisement (0xFE2C). */
    FAST_PAIR_ADVERT(1),
    /** Apple proximity pairing advertisement (company 0x004C): AirPods and Beats, 10% steps. */
    APPLE_ADVERT(1),
    /** Google Fast Pair message stream over RFCOMM: the device's own report. */
    FAST_PAIR_STREAM(3),
    /** The brand's own protocol over RFCOMM ([BrandProtocol]). */
    BRAND(4)
}

/** One source's levels for one device, and when they came. [protocol]: the [BrandProtocol.id], for the log. */
data class SourceReading(
    val levels: FastPairBattery.Levels, val source: BatterySource, val atMs: Long, val protocol: String? = null
)

/**
 * Picks the levels to show from all sources of one device. Pure. Never splits or invents a level: a source gives
 * left, right and case, or the device keeps its one standard level.
 */
object BatteryResolver {
    /** A device's own report older than this is stale: it is read again every [BrandBatteryReader.EVERY_MS]. */
    const val ASKED_STALE_MS = 30 * 60_000L
    /** Some bud must be this close to the standard level, when there is one. Wider than the broadcast's check. */
    const val MAX_GAP_PCT = 30

    /**
     * The best reading: the highest [BatterySource.rank], then the newest. Dropped: no level at all, a device's own
     * report older than [ASKED_STALE_MS], and a reading whose buds are all more than [MAX_GAP_PCT] from [standard]
     * (-1 or 0: no standard level, no check).
     */
    fun pick(readings: Collection<SourceReading>, nowMs: Long, standard: Int): SourceReading? =
        readings.filter { valid(it, nowMs, standard) }
            .maxWithOrNull(compareBy<SourceReading> { it.source.rank }.thenBy { it.atMs })

    fun valid(r: SourceReading, nowMs: Long, standard: Int): Boolean {
        val l = r.levels
        if (!l.any) return false
        if (r.source.rank > 1 && nowMs - r.atMs > ASKED_STALE_MS) return false
        val buds = listOf(l.left, l.right).filter { it >= 0 }
        if (standard > 0 && buds.isNotEmpty() && buds.none { abs(it - standard) <= MAX_GAP_PCT }) return false
        return true
    }
}
