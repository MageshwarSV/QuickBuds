package com.spizganed.quickbuds.bluetooth.battery

import com.spizganed.quickbuds.bluetooth.FastPairBattery
import java.util.UUID

/**
 * A brand's own way to ask earbuds for left, right and case, over RFCOMM. One object per protocol, listed in
 * [BrandProtocols.ALL]. Read only: [requests] holds battery queries and nothing else, from a published source
 * (see each protocol's KDoc). Some brands' other commands reset the earbuds: never add one here.
 */
interface BrandProtocol {
    /** A short name for the log and the compatibility list, e.g. "sony". */
    val id: String
    /** How far it is proven ([Status]). */
    val status: Status
    /** The RFCOMM service the earbuds offer for it. They must list it ([BrandProtocols.find]). */
    val uuid: UUID

    /** Whether earbuds with this [name] (and the services [uuids] they offer) speak it. */
    fun matches(name: String, uuids: Set<UUID>): Boolean = uuid in uuids

    /** Wait after connecting before the first request: some earbuds drop what comes too soon. */
    val settleMs: Long get() = 300L

    /** The battery queries to send, in order; empty when the earbuds send their levels by themselves on connect. */
    fun requests(): List<ByteArray>

    /** All bytes received so far: the levels once complete, else null (more bytes may come). */
    fun parse(received: ByteArray): FastPairBattery.Levels?

    /**
     * Verified: tested on real earbuds and the levels matched. Experimental: from published source code, not yet
     * tested here. Logged with each reading, so a test can confirm it.
     */
    enum class Status { VERIFIED, EXPERIMENTAL }
}

/**
 * The brand protocols QuickBuds knows, in the order they are tried: a brand's own service first, then the Fast
 * Pair message stream, then protocols on the generic SPP service (those also need the brand's name).
 */
object BrandProtocols {
    val ALL: List<BrandProtocol> = listOf(
        com.spizganed.quickbuds.bluetooth.battery.brands.GalaxyBuds,
        com.spizganed.quickbuds.bluetooth.battery.brands.GalaxyBuds.Legacy,
        com.spizganed.quickbuds.bluetooth.battery.brands.Nothing,
        FastPairStream,
        com.spizganed.quickbuds.bluetooth.battery.brands.BoatWuqi,
        com.spizganed.quickbuds.bluetooth.battery.brands.GalaxyBuds.Spp,
        com.spizganed.quickbuds.bluetooth.battery.brands.SoundPeats,
        com.spizganed.quickbuds.bluetooth.battery.brands.EarFun.AirPro4,
        com.spizganed.quickbuds.bluetooth.battery.brands.EarFun
    )

    /** The first protocol for these earbuds: they offer its service and match it. Null: none. */
    fun find(name: String, uuids: Set<UUID>): BrandProtocol? = ALL.firstOrNull { it.uuid in uuids && it.matches(name, uuids) }
}

/** Hex for the log: "AA 07 00". */
fun ByteArray.hex(max: Int = 64): String = take(max).joinToString(" ") { "%02X".format(it) } + if (size > max) " …" else ""
