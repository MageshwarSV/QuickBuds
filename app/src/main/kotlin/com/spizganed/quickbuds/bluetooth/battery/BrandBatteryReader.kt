package com.spizganed.quickbuds.bluetooth.battery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.spizganed.quickbuds.bluetooth.PacketLogger
import java.io.ByteArrayOutputStream

/**
 * Reads other brands' earbuds with their own protocol ([BrandProtocols]): a short RFCOMM session every [EVERY_MS]
 * while they are connected, the first [FIRST_MS] after they connect. Short, so the brand's own app finds the
 * channel free most of the time, and no link stays open with nobody reading it. A session that cannot connect is
 * tried again only after [RETRY_MS]. The raw bytes of a session without levels go to the log, so a test can
 * show what the earbuds sent. [onReading] runs on the main thread. Main thread.
 */
class BrandBatteryReader(private val onReading: (address: String, SourceReading) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val watched = HashMap<String, Runnable>()

    private fun log(msg: String) = PacketLogger.log("BRAND BATTERY: $msg")

    /** Starts reading [device] ([name]: its Bluetooth name) when a protocol fits it; else does nothing. */
    @SuppressLint("MissingPermission")
    fun watch(device: BluetoothDevice, name: String) {
        val address = device.address
        if (address in watched) return
        val uuids = runCatching { device.uuids?.map { it.uuid }?.toSet() }.getOrNull().orEmpty()
        val p = BrandProtocols.find(name, uuids) ?: return
        log("${p.id} (${p.status.name.lowercase()}) for these earbuds")
        val run = object : Runnable {
            override fun run() {
                // Levels: again in EVERY_MS. No connect, or no levels (a probe, a channel another app holds): later.
                session(device, p, this) { gotLevels -> if (watched[address] === this) main.postDelayed(this, if (gotLevels) EVERY_MS else RETRY_MS) }
            }
        }
        watched[address] = run
        main.postDelayed(run, FIRST_MS)
    }

    fun unwatch(address: String) {
        watched.remove(address)?.let { main.removeCallbacks(it) }
    }

    fun stop() {
        watched.values.forEach { main.removeCallbacks(it) }
        watched.clear()
    }

    /** One session on a background thread; [done] gets whether it read levels. */
    @SuppressLint("MissingPermission")
    private fun session(device: BluetoothDevice, p: BrandProtocol, watch: Runnable, done: (Boolean) -> Unit) {
        Thread {
            var socket: BluetoothSocket? = null
            var connected = false
            val received = ByteArrayOutputStream()
            var levels: com.spizganed.quickbuds.bluetooth.FastPairBattery.Levels? = null
            try {
                val s = device.createRfcommSocketToServiceRecord(p.uuid)
                socket = s
                // A blocked connect or read ends when the socket closes: the whole session has a time limit.
                Thread {
                    runCatching { Thread.sleep(CONNECT_MS + READ_MS) }
                    runCatching { s.close() }
                }.apply { isDaemon = true }.start()
                s.connect()
                connected = true
                Thread.sleep(p.settleMs)
                val out = s.outputStream
                for (r in p.requests()) {
                    out.write(r)
                    out.flush()
                    Thread.sleep(GAP_MS)
                }
                val input = s.inputStream
                val chunk = ByteArray(512)
                val end = SystemClock.elapsedRealtime() + READ_MS
                while (levels == null && SystemClock.elapsedRealtime() < end) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    received.write(chunk, 0, n)
                    levels = runCatching { p.parse(received.toByteArray()) }.getOrNull()
                }
            } catch (e: Exception) {
                if (!connected) log("${p.id}: not connected (${e.javaClass.simpleName}): next try in ${RETRY_MS / 60_000} min")
            } finally {
                runCatching { socket?.close() }
            }
            val got = levels
            val bytes = received.toByteArray()
            when {
                got != null -> log("${p.id}: L=${got.left} R=${got.right} C=${got.case}" +
                    " charging ${got.leftCharging}/${got.rightCharging}/${got.caseCharging} (${p.status.name.lowercase()})")
                connected -> log("${p.id}: no levels in ${bytes.size} bytes: ${bytes.hex()}")
            }
            main.post {
                // Only for the same connection: a session that ends after a disconnect and a quick reconnect
                // would otherwise show levels from before as new. Was: any watch of the address took them.
                if (got != null && watched[device.address] === watch) onReading(device.address, SourceReading(got,
                    if (p === FastPairStream) BatterySource.FAST_PAIR_STREAM else BatterySource.BRAND, System.currentTimeMillis(), p.id))
                done(got != null)
            }
        }.apply { isDaemon = true; name = "brand-battery" }.start()
    }

    companion object {
        /** After the earbuds connect: their audio comes up first. */
        const val FIRST_MS = 8_000L
        const val EVERY_MS = 10 * 60_000L
        const val RETRY_MS = 30 * 60_000L
        const val CONNECT_MS = 8_000L
        const val READ_MS = 5_000L
        /** Between two requests. */
        const val GAP_MS = 150L
    }
}
