package com.spizganed.quickbuds.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock

/**
 * Listens for Fast Pair battery broadcasts ([FastPairBattery]) in short windows: [LISTEN_MS] every [EVERY_MS], only
 * while [OtherEarbudsMonitor] asks (earbuds of another brand connected, battery card on). A filtered, low-power
 * scan: Android allows it with the screen off and stops it with the app. [onLevels] gets what [FastPairBattery.Picker]
 * trusts after each window, or null.
 */
class FastPairScanner(
    private val context: Context,
    private val onLevels: (FastPairBattery.Levels?) -> Unit,
    /** AirPods and Beats ([com.spizganed.quickbuds.bluetooth.battery.AppleAdvert]): the window's pick, or null. */
    private val onApple: (FastPairBattery.Levels?) -> Unit = {}
) {

    private val handler = Handler(Looper.getMainLooper())
    private val picker = FastPairBattery.Picker()
    private val applePicker = com.spizganed.quickbuds.bluetooth.battery.AppleAdvert.Picker()
    /** Set by [start]: what the window listens for. */
    private var fastPairOn = false
    private var appleOn = false
    private var running = false
    private var scanning = false
    /** The standard level of the connected earbuds, for the picker's cross-check. */
    var standardLevel: Int? = null

    /** The supported buds' own levels while they are connected: their broadcast is set aside. */
    var ownLevels: () -> FastPairBattery.Levels? = { null }

    /** The last verdict's kind, logged when it changes (not every 45 s). */
    private var lastLogged: String? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            record.getManufacturerSpecificData(com.spizganed.quickbuds.bluetooth.battery.AppleAdvert.COMPANY)
                ?.let { com.spizganed.quickbuds.bluetooth.battery.AppleAdvert.parse(it) }
                ?.let { levels -> handler.post { applePicker.onAdvert(result.rssi, levels) } }
            val data = record.getServiceData(FAST_PAIR) ?: return
            val levels = FastPairBattery.parse(data) ?: return
            handler.post { picker.onAdvert(result.device.address, result.rssi, levels, SystemClock.elapsedRealtime()) }
        }
    }

    private val cycle = object : Runnable {
        override fun run() {
            if (!running) return
            startWindow()
            handler.postDelayed({
                stopWindow()
                if (fastPairOn) {
                    val v = picker.decide(SystemClock.elapsedRealtime(), standardLevel, ownLevels())
                    val kind = v.reason.substringBefore(':') + (v.levels?.let { " L=${it.left} R=${it.right} C=${it.case}" } ?: "")
                    if (kind != lastLogged) { lastLogged = kind; PacketLogger.log("FASTPAIR: ${v.reason}") }
                    onLevels(v.levels)
                }
                if (appleOn) {
                    val a = applePicker.take()
                    val kind = "apple " + (a?.let { "L=${it.left} R=${it.right} C=${it.case}" } ?: "none near")
                    if (kind != lastApple) { lastApple = kind; PacketLogger.log("FASTPAIR: $kind") }
                    onApple(a)
                }
            }, LISTEN_MS)
            handler.postDelayed(this, EVERY_MS)
        }
    }

    /** Listens for Fast Pair ([fastPair]) and AirPods or Beats ([apple]) broadcasts; both false stops. */
    fun start(fastPair: Boolean = true, apple: Boolean = false) {
        if (!fastPair && !apple) return stop()
        val changed = fastPair != fastPairOn || apple != appleOn
        fastPairOn = fastPair
        appleOn = apple
        if (running && !changed) return
        if (running) stop().also { fastPairOn = fastPair; appleOn = apple }
        if (!allowed()) return
        running = true
        handler.post(cycle)
    }

    private var lastApple: String? = null

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopWindow()
        picker.clear()
        applePicker.take()
        lastLogged = null
        lastApple = null
    }

    @SuppressLint("MissingPermission")
    private fun startWindow() {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        val filters = listOfNotNull(
            if (fastPairOn) ScanFilter.Builder().setServiceData(FAST_PAIR, ByteArray(0)).build() else null,
            // Type and length only: the rest of the message changes.
            if (appleOn) ScanFilter.Builder().setManufacturerData(com.spizganed.quickbuds.bluetooth.battery.AppleAdvert.COMPANY,
                byteArrayOf(0x07, 0x19), byteArrayOf(-1, -1)).build() else null
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        scanning = runCatching { scanner.startScan(filters, settings, callback) }.isSuccess
    }

    @SuppressLint("MissingPermission")
    private fun stopWindow() {
        if (!scanning) return
        scanning = false
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    private fun allowed() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val FAST_PAIR: ParcelUuid = ParcelUuid.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")
        const val LISTEN_MS = 8_000L
        const val EVERY_MS = 45_000L
    }
}
