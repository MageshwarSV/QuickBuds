package com.spizganed.quickbuds.bluetooth.battery.brands

import com.spizganed.quickbuds.bluetooth.DeviceCatalog
import com.spizganed.quickbuds.bluetooth.FastPairBattery
import com.spizganed.quickbuds.bluetooth.battery.BrandProtocol
import java.util.UUID

/**
 * SoundPEATS earbuds: three battery reads in GAIA frames, vendor 0x000A ([GaiaVendorFrames]), over the standard
 * SPP service. Facts from OpenPeats PROTOCOL.md (MIT; on the wire with a SoundPEATS H3):
 * https://github.com/Wasabules/OpenPeats/blob/HEAD/PROTOCOL.md and Gadgetbridge soundpeats/SoundpeatsCommands.kt.
 *
 *     FF 04 00 00 00 0A 03 23    case          reply FF 03 00 02 00 0A 83 23 00 5F   (status 00, 95 %)
 *     FF 04 00 00 00 0A 03 06    left          reply …83 06 00 64                    (100 %)
 *     FF 04 00 00 00 0A 03 07    right         reply …83 07 00 64
 *
 * No charging flag. The earbuds ignore what comes in the first 600 ms after the link. 0x0317 is not battery on
 * these earbuds (EarFun's case read is): never sent here. The service is the generic SPP one, so the name must be
 * SoundPEATS too.
 */
object SoundPeats : BrandProtocol {
    override val id = "soundpeats"
    override val status = BrandProtocol.Status.EXPERIMENTAL
    override val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    override val settleMs = 800L

    private const val LEFT = 0x0306
    private const val RIGHT = 0x0307
    private const val CASE = 0x0323

    override fun matches(name: String, uuids: Set<UUID>) = DeviceCatalog.brandOf(name)?.name == "SoundPEATS"

    // The case first: once left and right have answered, its answer has come or does not come.
    override fun requests() = listOf(CASE, LEFT, RIGHT).map { GaiaVendorFrames.request(it) }

    override fun parse(received: ByteArray): FastPairBattery.Levels? =
        GaiaVendorFrames.levels(received, LEFT, RIGHT, CASE, zeroUnknown = false)
}

/**
 * EarFun earbuds: the same GAIA battery reads as [SoundPeats], with EarFun's own case read 0x0317, which
 * Gadgetbridge sends only to the Air Pro 4 (earfun/EarFunPacketEncoder.java, EarFunPacket.java,
 * EarFunResponseHandler.java): other models get left and right only. A level of 0 means not known there.
 */
object EarFun : BrandProtocol {
    override val id = "earfun"
    override val status = BrandProtocol.Status.EXPERIMENTAL
    override val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    override val settleMs = 800L

    private const val LEFT = 0x0306
    private const val RIGHT = 0x0307
    private const val CASE = 0x0317

    override fun matches(name: String, uuids: Set<UUID>) = DeviceCatalog.brandOf(name)?.name == "EarFun"

    override fun requests() = requestsFor(caseRead = false)

    internal fun requestsFor(caseRead: Boolean): List<ByteArray> =
        (if (caseRead) listOf(CASE) else emptyList()).plus(listOf(LEFT, RIGHT)).map { GaiaVendorFrames.request(it) }

    override fun parse(received: ByteArray): FastPairBattery.Levels? =
        GaiaVendorFrames.levels(received, LEFT, RIGHT, CASE, zeroUnknown = true)

    /** EarFun Air Pro 4: left, right and the case. Listed before [EarFun] in the registry. */
    object AirPro4 : BrandProtocol by EarFun {
        override val id = "earfun-airpro4"
        override fun matches(name: String, uuids: Set<UUID>) =
            DeviceCatalog.brandOf(name)?.name == "EarFun" && name.lowercase().contains("air pro 4")
        override fun requests() = requestsFor(caseRead = true)
    }
}
