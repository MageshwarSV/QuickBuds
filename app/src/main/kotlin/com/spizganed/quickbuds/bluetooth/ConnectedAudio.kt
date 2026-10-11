package com.spizganed.quickbuds.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * What is connected right now, asked of Bluetooth itself: the devices with phone audio up (A2DP, headset, LE Audio
 * or hearing aid).
 * Broadcasts can be late or never come (some phones hold them back for apps), so a decision that matters (is this
 * session still running, is this card still true) checks here first.
 */
object ConnectedAudio {

    /**
     * The connected audio devices, on the main thread. Empty with Bluetooth off; null when Bluetooth cannot be
     * asked (no permission), so the caller keeps what it has rather than ending everything.
     */
    @SuppressLint("MissingPermission")
    fun query(context: Context, result: (Map<String, BluetoothDevice>?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            main.post { result(emptyMap()) }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            main.post { result(null) }
            return
        }
        val found = LinkedHashMap<String, BluetoothDevice>()
        // A2DP and headset always; LE Audio and hearing aids where the phone has them. Buds on LE Audio drop A2DP
        // and the headset profile: without asking LE Audio they looked disconnected while playing.
        val required = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        // A profile the phone has switched off accepts the request and never answers: LE Audio only where it is on.
        val leAudio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { adapter.isLeAudioSupported == BluetoothStatusCodes.FEATURE_SUPPORTED }.getOrDefault(false)
        val optional = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(BluetoothProfile.HEARING_AID)
            if (leAudio) add(BluetoothProfile.LE_AUDIO)
        }
        var requiredLeft = required.size
        var optionalLeft = optional.size
        var failed = false
        var delivered = false
        fun deliver(value: Map<String, BluetoothDevice>?) {
            if (delivered) return
            delivered = true
            result(value)
        }
        fun finish() = main.post { deliver(if (failed) null else LinkedHashMap(found)) }
        fun answered(profile: Int) {
            if (profile in required) requiredLeft-- else optionalLeft--
            when {
                requiredLeft == 0 && optionalLeft == 0 -> finish()
                // An optional profile gets a moment more, never longer: it must not hold the answer back.
                requiredLeft == 0 && profile in required -> main.postDelayed({ finish() }, OPTIONAL_GRACE_MS)
            }
        }
        // A required profile that never answers must not hold the answer back either: then nothing is known.
        main.postDelayed({ deliver(null) }, TIMEOUT_MS)
        for (p in required + optional) {
            val asked = runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        runCatching { proxy.connectedDevices.forEach { found[it.address] = it } }
                            .onFailure { if (profile in required) failed = true }
                        runCatching { adapter.closeProfileProxy(profile, proxy) }
                        answered(profile)
                    }
                    override fun onServiceDisconnected(profile: Int) {}
                }, p)
            }.getOrDefault(false)
            // An optional profile the phone does not have is no failure.
            if (!asked) { if (p in required) failed = true; answered(p) }
        }
    }

    private const val TIMEOUT_MS = 3_000L
    private const val OPTIONAL_GRACE_MS = 500L
}
