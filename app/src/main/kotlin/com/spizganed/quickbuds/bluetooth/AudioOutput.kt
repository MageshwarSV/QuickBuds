package com.spizganed.quickbuds.bluetooth

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * The Bluetooth device the phone plays media on. Android hands audio to one Bluetooth device at a time (its active
 * device), and only that one is an audio output: so the outputs list names it.
 *
 * An app cannot choose the active device: that takes a system permission. [openSwitcher] opens Android's own output
 * switcher, where the user picks it.
 */
object AudioOutput {

    private val BLUETOOTH = buildSet {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        if (Build.VERSION.SDK_INT >= 31) { add(AudioDeviceInfo.TYPE_BLE_HEADSET); add(AudioDeviceInfo.TYPE_BLE_SPEAKER) }
    }

    /** The address of the Bluetooth device that plays media now; null when the phone plays elsewhere (or Android 8). */
    fun current(context: Context): String? = if (Build.VERSION.SDK_INT < 28) null else runCatching {
        context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.type in BLUETOOTH && it.address.isNotEmpty() }?.address?.uppercase()
    }.getOrNull()

    /** Android's output switcher (Android 14 and later); else the Bluetooth settings. */
    fun openSwitcher(context: Context) {
        if (Build.VERSION.SDK_INT >= 34 && runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }
                .getOrDefault(false)) return
        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    /** Calls [onChange] when the output changes: the old device leaves the outputs list, the new one joins it. */
    class Watcher(private val context: Context, private val onChange: () -> Unit) {
        private val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = changed(added)
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = changed(removed)
        }

        private fun changed(devices: Array<out AudioDeviceInfo>) {
            if (devices.any { it.type in BLUETOOTH }) onChange()
        }

        fun start() = context.getSystemService(AudioManager::class.java)
            ?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))

        fun stop() = context.getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(callback)
    }
}
