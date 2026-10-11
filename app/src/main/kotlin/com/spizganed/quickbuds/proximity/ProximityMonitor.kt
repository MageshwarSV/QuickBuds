package com.spizganed.quickbuds.proximity

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.ui.ThemeRes
import com.spizganed.quickbuds.usage.Usage

/**
 * Loss protection, wired to the buds' link. Lives in [BudsService] (the foreground service that already
 * holds the link), so it works with the app closed and the screen locked, and adds no service of its own.
 *
 * Reads RSSI every [ProximityConfig.SAMPLE_INTERVAL_MS], and only while: loss protection is on, the buds
 * are connected, and they are neither all in the case nor in an ear. Disconnected, in the case or worn:
 * no reads, no GATT client, no wake lock. Decisions are [ProximityEngine]'s.
 *
 * The first reads double as a probe: if [ProximityConfig.PROBE_FAILURES] reads in a row get no answer
 * and none ever did, this phone cannot read these buds' RSSI. The warning then stays off for them, the
 * result is stored for Settings, and usage tracking goes on.
 *
 * Screen off, the CPU may sleep between reads. While monitoring, a partial wake lock keeps reads going
 * for [ProximityConfig.WAKE_WINDOW_MS] after the screen goes off, the time it takes to walk away, then
 * lets the phone sleep.
 */
/**
 * [primary]: the supported buds' monitor. It owns the loss card with Play sound and the state Settings shows; a
 * monitor for another brand's device ([com.spizganed.quickbuds.bluetooth.OtherEarbudsMonitor]) has its own card,
 * without Play sound (its find tone is not reachable), and leaves Settings alone.
 */
class ProximityMonitor(private val context: Context, private val primary: Boolean = true) {

    private val handler = Handler(Looper.getMainLooper())
    private val engine = ProximityEngine()
    private var device: BluetoothDevice? = null
    private var source: GattRssiSource? = null
    private var polling = false
    private var awaiting = 0
    private var seq = 0
    private var failures = 0
    private var everRead = false
    private var screenOffAt = 0L
    private var screenReceiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    /** Raw readings since the last summary line: a minute of them goes to the log as min / median / max. */
    private val minute = ArrayList<Int>()
    private var minuteFrom = 0L

    val state: ProximityState get() = engine.state

    /** The link to [device] is up. */
    fun onConnected(device: BluetoothDevice) {
        this.device = device
        start()
    }

    /** The link went down; [reason] decides whether that alone may warn. */
    fun onDisconnected(reason: DropReason) {
        val effect = engine.onDisconnected(reason)
        if (effect != ProximityEffect.NONE) log("link down ($reason) in a weak signal")
        apply(effect)
        stopPolling()
        closeSource()
        publish()
    }

    /** A wear report: raw codes as in BudsConnectionManager.Listener.onWearState. */
    fun onWear(left: Int, right: Int) {
        val id = device?.address
        if (id != null && (left >= 0 || right >= 0)) Usage.savePlacementSeen(context, id)
        val before = engine.state
        apply(engine.onPlacement(Placement.of(left, right)))
        if (engine.state != before) log("placement ${engine.placement} -> ${engine.state}")
        updatePolling()
    }

    /** Settings › Loss protection or its sensitivity changed. */
    fun onSettingsChanged() {
        engine.sensitivity = sensitivity(context)
        val on = enabled(context)
        if (!on && engine.state != ProximityState.IDLE) {
            engine.stop()
            stopPolling()
            closeSource()
            LossNotifier.clear(context, key())
            publish()
        } else if (on && engine.state == ProximityState.IDLE && device != null && connected()) start()
    }

    fun destroy() {
        engine.stop()
        stopPolling()
        closeSource()
        publish()
    }

    /** True while the service's link is up; set by the service. */
    var connected: () -> Boolean = { false }

    private fun start() {
        engine.sensitivity = sensitivity(context)
        if (!enabled(context)) { engine.stop(); publish(); return }
        if (!hasConnectPermission()) {
            log("off: no Bluetooth permission")
            engine.stop()
            publish()
            return
        }
        failures = 0
        everRead = false
        engine.onConnected()
        // No address in the log: exported logs go out with problem reports.
        log("monitoring, ${engine.sensitivity}, state ${engine.state}")
        updatePolling()
    }

    private fun shouldPoll() = engine.state in POLLING

    private fun updatePolling() {
        if (shouldPoll() && !polling) {
            polling = true
            registerScreen()
            handler.post(tick)
        } else if (!shouldPoll() && polling) {
            stopPolling()
            // In the case or an ear for now: release the GATT client too.
            closeSource()
        }
        updateWake()
        publish()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!polling) return
            handler.postDelayed(this, ProximityConfig.SAMPLE_INTERVAL_MS)
            if (awaiting != 0) return
            val src = source ?: device?.let { GattRssiSource(context, it) }?.also { source = it }
            if (src == null || !src.open()) { onReading(null); return }
            val mine = ++seq
            awaiting = mine
            handler.postDelayed({
                if (awaiting == mine) { awaiting = 0; src.cancelPending(); onReading(null) }
            }, ProximityConfig.READ_TIMEOUT_MS)
            src.read { rssi -> handler.post { if (awaiting == mine) { awaiting = 0; onReading(rssi) } } }
        }
    }

    private fun onReading(rssi: Int?) {
        if (!polling) return
        val id = device?.address
        if (rssi == null) {
            failures++
            if (!everRead && failures >= ProximityConfig.PROBE_FAILURES) {
                log("no RSSI from this phone for these buds: loss warning off for them")
                if (id != null) Usage.saveRssiSupport(context, id, false)
                apply(engine.onRssiUnavailable())
                stopPolling()
                closeSource()
                publish()
            }
            return
        }
        failures = 0
        logMinute(rssi)
        if (!everRead) {
            everRead = true
            if (id != null) Usage.saveRssiSupport(context, id, true)
        }
        val before = engine.state
        val effect = engine.onRssi(rssi, SystemClock.elapsedRealtime())
        if (engine.state != before || effect != ProximityEffect.NONE) {
            log("rssi $rssi smoothed ${"%.1f".format(engine.filter.smoothed)} ${engine.filter.scale} " +
                "slope ${"%.1f".format(engine.filter.slopePer10s(SystemClock.elapsedRealtime()))}/10s: $before -> ${engine.state}")
        }
        apply(effect)
        updatePolling()
    }

    /** One line a minute: what the signal did, so a wrong warning (or a missed one) can be read back from the log. */
    private fun logMinute(rssi: Int) {
        val now = SystemClock.elapsedRealtime()
        if (minute.isEmpty()) minuteFrom = now
        minute += rssi
        if (now - minuteFrom < 60_000) return
        val sorted = minute.sorted()
        log("minute: rssi min ${sorted.first()} median ${sorted[sorted.size / 2]} max ${sorted.last()} (${sorted.size} reads), " +
            "smoothed ${"%.1f".format(engine.filter.smoothed)} ${engine.filter.scale}, ${engine.placement}, ${engine.state}")
        minute.clear()
    }

    private fun apply(effect: ProximityEffect) {
        when (effect) {
            ProximityEffect.WARN -> { log("WARNING: signal weak and falling"); LossNotifier.warn(context, linkLost = false, key = key()) }
            ProximityEffect.WARN_LINK_LOST -> { log("WARNING: link lost in a weak signal"); LossNotifier.warn(context, linkLost = true, key = key()) }
            ProximityEffect.CLEAR -> LossNotifier.clear(context, key())
            ProximityEffect.NONE -> {}
        }
    }

    private fun stopPolling() {
        minute.clear()
        polling = false
        awaiting = 0
        handler.removeCallbacksAndMessages(null)
        unregisterScreen()
        releaseWake()
    }

    private fun closeSource() {
        source?.close()
        source = null
    }

    // --- Screen-off wake window ---

    private fun registerScreen() {
        if (screenReceiver != null) return
        val pm = context.getSystemService(PowerManager::class.java)
        screenOffAt = if (pm?.isInteractive == false) SystemClock.elapsedRealtime() else 0L
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                screenOffAt = if (intent.action == Intent.ACTION_SCREEN_OFF) SystemClock.elapsedRealtime() else 0L
                updateWake()
            }
        }
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        runCatching { context.registerReceiver(r, filter) }.onSuccess { screenReceiver = r }
    }

    private fun unregisterScreen() {
        screenReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        screenReceiver = null
    }

    /** Holds the CPU awake while polling, screen off, inside the window; never past it. */
    private fun updateWake() {
        val left = if (screenOffAt == 0L) 0L else ProximityConfig.WAKE_WINDOW_MS - (SystemClock.elapsedRealtime() - screenOffAt)
        if (polling && left > 0) {
            val wl = wakeLock ?: context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuickBuds:lossProtection")
                ?.apply { setReferenceCounted(false) }
                ?.also { wakeLock = it }
            // The timeout is the guarantee: the lock ends with the window even if nothing else happens.
            runCatching { wl?.acquire(left) }
        } else releaseWake()
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
    }

    private fun hasConnectPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** The card's owner for [LossNotifier]: null for the supported buds, else the device's address. */
    private fun key(): String? = if (primary) null else device?.address ?: "other"

    /** The user tapped "I have them" on this monitor's card. */
    fun onWarningDismissed() = engine.onWarningDismissed()

    private fun publish() { if (primary) lastState = engine.state }

    private fun log(msg: String) = PacketLogger.log("LOSS: $msg")

    companion object {
        /** Settings › Loss protection (default off: it reads the radio and may notify). */
        const val KEY_ENABLED = "lossProtection"
        /** Settings › Sensitivity, a [Sensitivity] name. */
        const val KEY_SENSITIVITY = "lossSensitivity"

        private val POLLING = setOf(ProximityState.MONITORING, ProximityState.WEAK_SIGNAL,
            ProximityState.WARNING_SENT, ProximityState.RECOVERED)

        /** The running monitor's state, for Settings; IDLE when the service is not running. */
        @Volatile var lastState: ProximityState = ProximityState.IDLE
            private set

        fun enabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false) && BudsService.backgroundAllowed(c)

        fun sensitivity(c: Context): Sensitivity =
            runCatching { Sensitivity.valueOf(prefs(c).getString(KEY_SENSITIVITY, null) ?: Sensitivity.MEDIUM.name) }
                .getOrDefault(Sensitivity.MEDIUM)

        private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
    }
}
