package com.spizganed.quickbuds.bluetooth

import android.os.Handler
import android.os.Looper

/**
 * The other devices connected now, for the home screen's "Connected devices" tile: what [OtherEarbudsMonitor]
 * tracks, in the order they connected. The app and the service share one process; listeners run on the main thread.
 */
object ConnectedDevices {

    /** [level] -1 until the device reports one; [parts] left, right and case when Fast Pair is trusted. */
    data class Entry(
        val address: String, val name: String, val kind: DeviceCatalog.Kind?, val level: Int,
        val parts: FastPairBattery.Levels?
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val listeners = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized fun all(): List<Entry> = entries.values.toList()

    fun put(e: Entry) {
        synchronized(this) { if (entries[e.address] == e) return; entries[e.address] = e }
        changed()
    }

    fun remove(address: String) {
        synchronized(this) { entries.remove(address) ?: return }
        changed()
    }

    fun addListener(l: () -> Unit) { main.post { listeners += l } }
    fun removeListener(l: () -> Unit) { main.post { listeners -= l } }

    private fun changed() = main.post { listeners.toList().forEach { it() } }
}
