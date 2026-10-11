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
class FastPairScanner(private val context: Context, private val onLevels: (FastPairBattery.Levels?) -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    private val picker = FastPairBattery.Picker()
    private var running = false
    private var scanning = false
    /** The standard level of the connected earbuds, for the picker's cross-check. */
    var standardLevel: Int? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getServiceData(FAST_PAIR) ?: return
            val levels = FastPairBattery.parse(data) ?: return
            handler.post { picker.onAdvert(result.device.address, result.rssi, levels, SystemClock.elapsedRealtime()) }
        }
    }

    private val cycle = object : Runnable {
        override fun run() {
            if (!running) return
            startWindow()
            handler.postDelayed({ stopWindow(); onLevels(picker.pick(SystemClock.elapsedRealtime(), standardLevel)) }, LISTEN_MS)
            handler.postDelayed(this, EVERY_MS)
        }
    }

    fun start() {
        if (running || !allowed()) return
        running = true
        handler.post(cycle)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopWindow()
        picker.clear()
    }

    @SuppressLint("MissingPermission")
    private fun startWindow() {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceData(FAST_PAIR, ByteArray(0)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        scanning = runCatching { scanner.startScan(listOf(filter), settings, callback) }.isSuccess
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
