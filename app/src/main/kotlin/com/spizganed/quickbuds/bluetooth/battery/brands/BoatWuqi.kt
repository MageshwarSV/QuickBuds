package com.spizganed.quickbuds.bluetooth.battery.brands

import com.spizganed.quickbuds.bluetooth.DeviceCatalog
import com.spizganed.quickbuds.bluetooth.FastPairBattery
import com.spizganed.quickbuds.bluetooth.battery.BrandProtocol
import java.util.UUID

/**
 * boAt earbuds with a WuQi chip: a PROBE. It sends the battery read and returns no levels, so the reader logs
 * the raw reply; a test on real earbuds gives the reply layout, which no source documents yet.
 *
 *     08 EE 00 00 00  command u16  length u8 (whole frame)  00  payload  checksum (sum of all bytes before, low 8 bits)
 *     replies: 09 FF 00 00 01 …, the same layout
 *     battery read: 08 EE 00 00 00 01 05 0A 00 06
 *
 * Service: RFCOMM UUID 0x7034. Facts from bezalelsamuel/boat-tws-commands chipsets/wuqi.md (tested on a Nirvana
 * Ion ANC; no license, so facts only). Command 05 01 is a factory reset there, with the same checksum as the battery
 * read: [READ] is a fixed constant, checked byte by byte in the tests. The earbuds also push the battery by
 * themselves.
 */
object BoatWuqi : BrandProtocol {
    override val id = "boat-wuqi-probe"
    override val status = BrandProtocol.Status.EXPERIMENTAL
    override val uuid: UUID = UUID.fromString("00007034-0000-1000-8000-00805F9B34FB")

    /** Read battery (command 01 05). Never built from parts: see the KDoc. */
    val READ = byteArrayOf(0x08, 0xEE.toByte(), 0x00, 0x00, 0x00, 0x01, 0x05, 0x0A, 0x00, 0x06)

    override fun matches(name: String, uuids: Set<UUID>) = DeviceCatalog.brandOf(name)?.name == "boAt"

    override fun requests() = listOf(READ.copyOf())

    /** A probe: the reply layout is not known yet, so nothing is read from it (the reader logs the bytes). */
    override fun parse(received: ByteArray): FastPairBattery.Levels? = null
}
