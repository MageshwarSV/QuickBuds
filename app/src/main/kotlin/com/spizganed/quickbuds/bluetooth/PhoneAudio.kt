package com.spizganed.quickbuds.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build

/**
 * The phone's own audio link to a device, on or off, the way HeyMelody's Connect/Disconnect does: the hidden
 * `connect()`/`disconnect()` on the A2DP and Headset proxies, by reflection (and LE Audio, where the phone has it).
 * Headset `connect()` is refused for ordinary apps, so connecting asks A2DP only and the system brings HFP up by
 * itself (~10 s). Connecting an already-connected profile is a no-op. Any device: the supported buds' Disconnect
 * button ([BudsConnectionManager.setPhoneAudio]) and the sleep timer ([disconnectAll]) both use it.
 */
object PhoneAudio {

    fun set(context: Context, device: BluetoothDevice, on: Boolean, log: (String) -> Unit = { PacketLogger.log("AUDIO: $it") }) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val method = if (on) "connect" else "disconnect"
        val profiles = if (on) listOf(BluetoothProfile.A2DP) else buildList {
            add(BluetoothProfile.HEADSET)
            add(BluetoothProfile.A2DP)
            // LE Audio only where the phone has it switched on: a profile that is off never answers.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                runCatching { adapter.isLeAudioSupported == android.bluetooth.BluetoothStatusCodes.FEATURE_SUPPORTED }
                    .getOrDefault(false)) add(BluetoothProfile.LE_AUDIO)
        }
        for (p in profiles) {
            runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        try {
                            val ok = proxy.javaClass.getMethod(method, BluetoothDevice::class.java).invoke(proxy, device)
                            log("Audio $method profile=$profile -> $ok")
                        } catch (e: Exception) {
                            log("Audio $method profile=$profile failed: ${e.cause ?: e}")
                        } finally {
                            adapter.closeProfileProxy(profile, proxy)
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) {}
                }, p)
            }
        }
    }

    /**
     * Every device with phone audio up now ([ConnectedAudio]) that is headphones or a speaker ([AudioDevice]), or
     * the supported buds: disconnected. [done] gets how many; -1 when Bluetooth could not be asked.
     */
    fun disconnectAll(context: Context, done: (Int) -> Unit) {
        ConnectedAudio.query(context) { connected ->
            if (connected == null) return@query done(-1)
            val targets = connected.values.filter { BudsDevice.isBuds(context, it) || AudioDevice.isAudio(it) }
            targets.forEach { set(context, it, on = false) }
            done(targets.size)
        }
    }
}
