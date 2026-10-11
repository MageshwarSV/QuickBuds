package com.spizganed.quickbuds.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.IntentCompat
import com.spizganed.quickbuds.usage.Usage

class KeepAliveReceiver : BroadcastReceiver() {

    /** ACL-only fallback: long enough for A2DP/HFP to come up and connect first. */
    private val ACL_FALLBACK_MS = 8_000L
    /** BluetoothDevice.EXTRA_TRANSPORT (API 33), spelled out so older SDK levels compile it the same. */
    private val EXTRA_TRANSPORT = "android.bluetooth.device.extra.TRANSPORT"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

        if (device == null) return
        val buds = BudsDevice.isBuds(context, device)
        if (!buds && !AudioDevice.isAudio(device)) return

        // Usage statistics follow the system's own link to the buds, any model and any brand, with or
        // without the background service. One ACL link is the whole pair, so left and right never count twice.
        // Classic (1) or LE (2), Android 13+: whether LE links reach the usage sessions is not known yet.
        if (action == BluetoothDevice.ACTION_ACL_CONNECTED || action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
            PacketLogger.log("USAGE: ${action.substringAfterLast('.')} transport=${intent.getIntExtra(EXTRA_TRANSPORT, -1)}")
        }
        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> recordUsage { Usage.connected(context, device, newLink = true, done = it) }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> recordUsage { Usage.disconnected(context, device.address, done = it) }
        }

        if (!BudsService.backgroundAllowed(context)) {
            Log.d("BudsConn", "KeepAlive: $action ignored (background service off)")
            return
        }
        if (!buds) return otherEarbuds(context, action, intent, device)

        // Connect when the AUDIO link is up (A2DP or HFP connected), not on the bare ACL link:
        // on ACL the buds are not ready for RFCOMM yet, so that connect used to fail and fall
        // back to 5 s retries. ACL still schedules a late connect, in case no audio profile ever
        // comes up (media audio switched off for the buds); it does nothing if already connected.
        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                BudsDevice.remember(context, device)
                Log.d("BudsConn", "KeepAlive: ACL_CONNECTED (fallback connect in ${ACL_FALLBACK_MS}ms)")
                fireForceConnect(context, ACL_FALLBACK_MS)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.d("BudsConn", "KeepAlive: ACL_DISCONNECTED (no action)")
            }
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED" -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                Log.d("BudsConn", "KeepAlive: audio profile state=$state ($action)")
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    BudsDevice.remember(context, device)
                    fireForceConnect(context, 0L)
                }
            }
        }
    }

    /**
     * Earbuds of another brand ([OtherEarbudsMonitor]): the service starts for them once their audio is up, and
     * only when one of their features is on. No RFCOMM connect: they do not speak the supported protocol.
     */
    private fun otherEarbuds(context: Context, action: String, intent: Intent, device: BluetoothDevice) {
        val connected = when (action) {
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED" ->
                if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) true else return
            else -> return
        }
        if (connected && !OtherEarbudsMonitor.wanted(context)) return
        Log.d("BudsConn", "KeepAlive: other earbuds ${if (connected) "connected" else "disconnected"}")
        runCatching {
            context.startService(Intent(context, BudsService::class.java).setAction(BudsService.ACTION_OTHER_EARBUDS)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, device).putExtra(BudsService.EXTRA_CONNECTED, connected))
        }.onFailure { Log.d("BudsConn", "KeepAlive: service start refused: ${it.message}") }
    }

    /** Keeps the process alive until the database write is done (goAsync), so a cold start does not lose it. */
    private fun recordUsage(write: (done: () -> Unit) -> Unit) {
        val pending = goAsync()
        write { pending.finish() }
    }

    private fun fireForceConnect(context: Context, delayMs: Long) {
        val serviceIntent = Intent(context, BudsService::class.java)
        serviceIntent.action = BudsService.ACTION_FORCE_CONNECT
        serviceIntent.putExtra(BudsService.EXTRA_DELAY_MS, delayMs)
        context.startService(serviceIntent)
    }
}