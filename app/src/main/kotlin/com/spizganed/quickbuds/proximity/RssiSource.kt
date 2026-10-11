package com.spizganed.quickbuds.proximity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.content.Context

/** Reads the signal strength of the link to one device. */
interface RssiSource {
    /** Prepares the source; false when it cannot exist on this phone. */
    fun open(): Boolean
    /** Asks for one reading; [result] gets the RSSI, or null when none came. May run on any thread. */
    fun read(result: (Int?) -> Unit)
    fun close()
}

/**
 * RSSI through Android's one public read, [BluetoothGatt.readRemoteRssi]. The buds talk RFCOMM, not GATT,
 * so no GATT client exists to reuse; this opens exactly one, for the buds' address, and only while loss
 * protection monitors connected buds.
 *
 * The client asks for the Classic transport, so it rides the ACL link already up for audio and our
 * RFCOMM socket: no second radio link and no LE scanning. Android answers the read with the RSSI of the
 * device's existing link, whether or not the buds accept GATT over Classic. Whether a phone's Bluetooth
 * stack answers for a Classic-only link differs between phones, so nothing is assumed: [ProximityMonitor]
 * probes it and turns the warning off for these buds if no reading ever comes.
 */
@SuppressLint("MissingPermission")
class GattRssiSource(private val context: Context, private val device: BluetoothDevice) : RssiSource {

    private var gatt: BluetoothGatt? = null
    private var pending: ((Int?) -> Unit)? = null
    private val lock = Any()

    private val callback = object : BluetoothGattCallback() {
        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            val cb = synchronized(lock) { pending.also { pending = null } }
            cb?.invoke(if (status == BluetoothGatt.GATT_SUCCESS) rssi else null)
        }
    }

    override fun open(): Boolean {
        if (gatt != null) return true
        gatt = runCatching { device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_BREDR) }.getOrNull()
        return gatt != null
    }

    override fun read(result: (Int?) -> Unit) {
        val g = gatt ?: return result(null)
        synchronized(lock) { pending = result }
        // False until the client is registered (a moment after open), or when the stack refuses.
        val asked = runCatching { g.readRemoteRssi() }.getOrDefault(false)
        if (!asked) synchronized(lock) { pending.also { pending = null } }?.invoke(null)
    }

    /** Drops a reading still in flight: its callback finds nothing to answer. */
    fun cancelPending() = synchronized(lock) { pending = null }

    override fun close() {
        cancelPending()
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
    }
}
