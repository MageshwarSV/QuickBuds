package com.spizganed.quickbuds.calls

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * Auto-answer: a ringing call is answered after the chosen delay, when earbuds are connected for calls, so it is
 * heard in them. Nothing happens when the call stops ringing first (the user answered or rejected it), when no
 * earbuds are connected for calls, or without the two phone permissions. Pure rules here; [AutoAnswerMonitor] runs them.
 */
object AutoAnswer {
    const val KEY_ENABLED = "autoAnswer"
    const val KEY_DELAY = "autoAnswerDelay"
    val DELAYS = listOf(3, 5, 10, 15)
    const val DEFAULT_DELAY = 5

    val PERMISSIONS = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false) && permitted(c) && BudsService.backgroundAllowed(c)

    fun delaySeconds(c: Context) = prefs(c).getInt(KEY_DELAY, DEFAULT_DELAY).takeIf { it in DELAYS } ?: DEFAULT_DELAY

    fun permitted(c: Context) = PERMISSIONS.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** At the end of the delay: answer only if the call still rings and earbuds are connected for calls. */
    fun shouldAnswer(stillRinging: Boolean, earbudsConnected: Boolean): Boolean = stillRinging && earbudsConnected
}

/**
 * In [BudsService] while it runs: the phone state broadcast says when a call rings; after the delay, if the call still
 * rings and earbuds are connected for calls, it is answered.
 * "Connected for calls" is the headset profile (or LE Audio), not media audio or QuickBuds' own link: a call goes to
 * earbuds only over that profile. Without it the call would open on the phone's earpiece, with nobody holding it.
 */
class AutoAnswerMonitor(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    private val answer = Runnable { tryAnswer() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    if (!AutoAnswer.enabled(context)) return
                    // The broadcast comes twice (with and without the number): one timer.
                    handler.removeCallbacks(answer)
                    handler.postDelayed(answer, AutoAnswer.delaySeconds(context) * 1000L)
                    PacketLogger.log("CALL: ringing, auto-answer in ${AutoAnswer.delaySeconds(context)} s if the earbuds are connected")
                }
                // Answered or rejected by the user, or ended: nothing to do.
                TelephonyManager.EXTRA_STATE_OFFHOOK, TelephonyManager.EXTRA_STATE_IDLE -> handler.removeCallbacks(answer)
            }
        }
    }

    /** Registered while the feature is on and allowed; called on start and when the setting changes. */
    fun update() {
        val want = AutoAnswer.enabled(context)
        if (want && !registered) {
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            // A system broadcast: exported, so the system's sender reaches it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
            registered = true
        } else if (!want && registered) {
            stop()
        }
    }

    fun stop() {
        handler.removeCallbacks(answer)
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    @SuppressLint("MissingPermission")
    private fun tryAnswer() {
        if (!AutoAnswer.enabled(context)) return
        val ringing = runCatching {
            context.getSystemService(TelephonyManager::class.java)?.callState == TelephonyManager.CALL_STATE_RINGING
        }.getOrDefault(false)
        if (!ringing) return
        callHeadset { name ->
            if (!AutoAnswer.shouldAnswer(ringing, name != null)) {
                PacketLogger.log("CALL: not answered: no earbuds connected for calls")
                return@callHeadset
            }
            runCatching {
                @Suppress("DEPRECATION")
                context.getSystemService(TelecomManager::class.java)?.acceptRingingCall()
                PacketLogger.log("CALL: auto-answered after ${AutoAnswer.delaySeconds(context)} s on $name")
            }.onFailure { PacketLogger.error("auto-answer", it) }
        }
    }

    /**
     * The name of a device connected for calls (headset profile, or LE Audio where the phone has it), or null.
     * On the main thread; null when Bluetooth is off, cannot be asked, or does not answer in time.
     */
    @SuppressLint("MissingPermission")
    private fun callHeadset(result: (String?) -> Unit) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return result(null)
        // A profile the phone has switched off accepts the request and never answers: LE Audio only where it is on.
        val leAudio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { adapter.isLeAudioSupported == BluetoothStatusCodes.FEATURE_SUPPORTED }.getOrDefault(false)
        val profiles = if (leAudio) listOf(BluetoothProfile.HEADSET, BluetoothProfile.LE_AUDIO) else listOf(BluetoothProfile.HEADSET)
        var left = profiles.size
        var found: String? = null
        var delivered = false
        fun deliver() {
            if (delivered) return
            delivered = true
            result(found)
        }
        handler.postDelayed({ deliver() }, HEADSET_TIMEOUT_MS)
        for (p in profiles) {
            val asked = runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        runCatching { proxy.connectedDevices.firstOrNull() }.getOrNull()?.let { d ->
                            if (found == null) found = runCatching { d.name }.getOrNull() ?: d.address
                        }
                        runCatching { adapter.closeProfileProxy(profile, proxy) }
                        handler.post { if (--left == 0) deliver() }
                    }
                    override fun onServiceDisconnected(profile: Int) {}
                }, p)
            }.getOrDefault(false)
            if (!asked && --left == 0) handler.post { deliver() }
        }
    }

    private companion object {
        const val HEADSET_TIMEOUT_MS = 2_000L
    }
}
