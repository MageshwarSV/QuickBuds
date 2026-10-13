package com.spizganed.quickbuds.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.protocol.OpoProtocol
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * Which paired device is "the buds": the address saved last (the buds whose audio connected most recently, see
 * [KeepAliveReceiver]), else the first bonded device that [isBuds].
 */
@SuppressLint("MissingPermission")
object BudsDevice {

    private const val KEY_ADDRESS = "budsAddress"
    private val SPP_UUIDS = setOf(OpoProtocol.SPP_UUID_PRIMARY, OpoProtocol.SPP_UUID_FALLBACK)

    /** The brands that speak the OPPO protocol (`models.json`). */
    private val PROTOCOL_BRANDS = setOf("OnePlus", "OPPO", "realme", "DIZO")

    /** Whether a device is supported buds, and why: for the log. */
    data class Verdict(val buds: Boolean, val reason: String)

    /**
     * Pure rules, in order:
     *  1. a `models.json` name: yes;
     *  2. a name of another brand the catalog knows (boAt, JBL, ...): no, whatever it offers;
     *  3. the OPPO SPP UUID: yes (a renamed pair of supported buds);
     *  4. else no.
     * Was: the UUID alone was enough. It belongs to the chip maker (`...-00025B00A5A5`), not to OPPO: a boAt
     * Airdopes 800 offers it too, was taken for realme buds, and QuickBuds kept trying to connect to it.
     */
    fun decide(name: String?, offersSpp: Boolean, modelNames: Set<String>): Verdict {
        if (name != null && name in modelNames) return Verdict(true, "model name")
        val brand = DeviceCatalog.brandOf(name)?.name
        if (brand != null && brand !in PROTOCOL_BRANDS) return Verdict(false, "$brand, another brand")
        if (offersSpp) return Verdict(true, "OPPO SPP UUID" + (brand?.let { ", $it" } ?: ", brand unknown"))
        return Verdict(false, "no model name, no OPPO SPP UUID")
    }

    private fun verdict(context: Context, device: BluetoothDevice): Verdict = runCatching {
        decide(
            device.name,
            device.uuids?.any { it.uuid.toString().uppercase() in SPP_UUIDS } == true,
            ModelCatalog.all(context).map { it.name }.toSet()
        )
    }.getOrDefault(Verdict(false, "unreadable"))

    fun isBuds(context: Context, device: BluetoothDevice): Boolean = verdict(context, device).buds

    fun remember(context: Context, device: BluetoothDevice) {
        if (prefs(context).getString(KEY_ADDRESS, null) == device.address) return
        prefs(context).edit().putString(KEY_ADDRESS, device.address).apply()
        PacketLogger.log("BUDS: ${device.name} picked (${verdict(context, device).reason})")
    }

    // ponytail: with several matching buds and none saved yet, the first bonded match wins; add a
    // picker if users report it (the audio-connect broadcast normally saves the right one first).
    fun find(context: Context): BluetoothDevice? {
        val bonded = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
        }.getOrNull().orEmpty()
        val saved = prefs(context).getString(KEY_ADDRESS, null)
        bonded.firstOrNull { it.address == saved }?.let { device ->
            if (isBuds(context, device)) return device
            forget(context, device)
        }
        return bonded.firstOrNull { isBuds(context, it) }?.also { remember(context, it) }
    }

    /**
     * A device saved under the old rule that is not supported buds: it goes, with the model read from it (a
     * "realme Buds Wireless 3" that was a boAt), so nothing tries to connect to it again.
     */
    private fun forget(context: Context, device: BluetoothDevice) {
        PacketLogger.log("BUDS: ${device.name} dropped (${verdict(context, device).reason})")
        prefs(context).edit().remove(KEY_ADDRESS).remove(ModelCatalog.KEY_DEVICE_NAME)
            .remove(Capabilities.KEY_PRODUCT_ID).remove(ModelCatalog.KEY_MANUAL).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
}
