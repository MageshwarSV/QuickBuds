package com.spizganed.quickbuds.usage

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.spizganed.quickbuds.bluetooth.AudioDevice
import com.spizganed.quickbuds.bluetooth.DeviceCatalog
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.ui.ThemeRes
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Usage tracking for the whole app: the one database, the one writer thread, and the entry points the
 * Bluetooth side calls ([connected], [disconnected]). Every write runs on [executor], in order, so a
 * burst of duplicate callbacks is applied one by one and [UsageTracker]'s idempotence holds.
 *
 * Fed by any supported buds, whatever the model: the system's ACL connect / disconnect broadcasts
 * ([com.spizganed.quickbuds.bluetooth.KeepAliveReceiver]) and, while [com.spizganed.quickbuds.bluetooth.BudsService]
 * runs, our RFCOMM link and the buds' own reports as heartbeats.
 */
object Usage {

    /** Settings › Usage tracking (default on). Off: no new sessions, and an open one ends. */
    const val KEY_ENABLED = "usageTracking"
    /** Settings › History retention, in days ([UsageRetention.CHOICES]). */
    const val KEY_RETENTION = "usageRetentionDays"

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "usage").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Holds only the application context (SQLiteOpenHelper keeps that one), which lives as long as the process. */
    @SuppressLint("StaticFieldLeak")
    @Volatile private var db: UsageDatabase? = null
    @Volatile private var tracker: UsageTracker? = null

    /** The system clocks. The boot count is read once: a reboot always starts a new process. */
    private class SystemUsageClock(private val boot: Int) : UsageClock {
        override fun wallMs() = System.currentTimeMillis()
        override fun elapsedMs() = SystemClock.elapsedRealtime()
        override fun bootCount() = boot
    }

    private fun store(context: Context): UsageDatabase = db ?: synchronized(this) {
        db ?: UsageDatabase(context).also { db = it }
    }

    private fun tracker(context: Context): UsageTracker = tracker ?: synchronized(this) {
        tracker ?: run {
            val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
            UsageTracker(store(context), SystemUsageClock(boot))
        }.also { tracker = it }
    }

    fun enabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)

    fun retentionDays(context: Context) =
        prefs(context).getInt(KEY_RETENTION, UsageRetention.DEFAULT_DAYS).takeIf { it in UsageRetention.CHOICES }
            ?: UsageRetention.DEFAULT_DAYS

    /** Called when something changed, on the main thread. */
    fun addListener(l: () -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /**
     * [device] is connected: a connect event or a sign of life. Idempotent. [newLink] = the system's ACL
     * connect ([UsageTracker.onConnected]). [done] runs on the writer thread when the write is finished
     * (a receiver's goAsync).
     */
    fun connected(context: Context, device: BluetoothDevice, newLink: Boolean = false, done: (() -> Unit)? = null) {
        val info = describe(context, device)
        write(context, done) {
            if (enabled(context)) tracker(context).onConnected(info, newLink) else remember(context, info)
        }
    }

    /** Devices written in this process, so a repeat event does not rewrite an unchanged row. */
    private val remembered = HashMap<String, UsageDevice>()

    /**
     * Keeps [device] in the device list even with usage tracking off: the list of earbuds that ever connected to
     * this phone (Model screen). True when the row changed.
     */
    private fun remember(context: Context, device: UsageDevice): Boolean {
        if (remembered[device.id] == device) return false
        store(context).upsertDevice(device)
        remembered[device.id] = device
        return true
    }

    /** The whole device disconnected (ACL). Idempotent. */
    fun disconnected(context: Context, address: String, done: (() -> Unit)? = null) =
        write(context, done) {
            tracker(context).onDisconnected(address)
            UsageRetention.apply(store(context), System.currentTimeMillis(), ZoneId.systemDefault(), retentionDays(context))
            true
        }

    /** Bluetooth turned off. */
    fun allDisconnected(context: Context) = write(context) { tracker(context).onAllDisconnected(); true }

    /** After the process starts: ends sessions nothing can still be connected for. */
    fun reconcile(context: Context, connected: Set<String>?) = write(context) { tracker(context).reconcile(connected); true }

    /** Settings › Usage tracking. Off ends every open session now. */
    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) allDisconnected(context)
    }

    /** Settings › History retention: applies the new period at once. */
    fun setRetentionDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_RETENTION, days).apply()
        write(context) { UsageRetention.apply(store(context), System.currentTimeMillis(), ZoneId.systemDefault(), days); true }
    }

    fun savePlacementSeen(context: Context, deviceId: String) = write(context) {
        val s = store(context)
        val caps = s.capabilities(deviceId)
        if (caps.placementReports != true) s.saveCapabilities(deviceId, caps.copy(placementReports = true))
        false
    }

    fun saveRssiSupport(context: Context, deviceId: String, supported: Boolean) = write(context) {
        val s = store(context)
        val caps = s.capabilities(deviceId)
        if (caps.rssi != supported) s.saveCapabilities(deviceId, caps.copy(rssi = supported))
        false
    }

    /** What the statistics screen draws from. */
    class Snapshot(
        val devices: List<UsageDevice>,
        val spans: List<UsageSpan>,
        val summaries: List<DaySummary>,
        val capabilities: Map<String, UsageCapabilities>,
        val retentionDays: Int,
        val nowMs: Long
    ) {
        /** Nothing recorded at all. A session that started a second ago counts: the screen shows it. */
        val isEmpty: Boolean get() = spans.isEmpty() && summaries.none { it.totalMs > 0 }
        val hasOpenSession: Boolean get() = spans.any { it.open }
    }

    /** Applies retention, then reads everything on the writer thread; [result] runs on the main thread. */
    fun load(context: Context, result: (Snapshot) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val snapshot = try {
                val s = store(app)
                val days = retentionDays(app)
                UsageRetention.apply(s, System.currentTimeMillis(), ZoneId.systemDefault(), days)
                val t = tracker(app)
                val devices = s.devices()
                Snapshot(devices, s.sessions().map { t.resolve(it) }, s.summaries(),
                    devices.associate { it.id to s.capabilities(it.id) }, days, System.currentTimeMillis())
            } catch (e: Exception) {
                PacketLogger.error("usage load", e)
                Snapshot(emptyList(), emptyList(), emptyList(), emptyMap(), retentionDays(app), System.currentTimeMillis())
            }
            main.post { result(snapshot) }
        }
    }

    /** Reads one device's capabilities; [result] runs on the main thread. */
    fun capabilities(context: Context, deviceId: String, result: (UsageCapabilities) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val caps = runCatching { store(app).capabilities(deviceId) }.getOrDefault(UsageCapabilities())
            main.post { result(caps) }
        }
    }

    /** Runs [block] on the writer thread; listeners hear of it when it returns true (what screens show changed). */
    private fun write(context: Context, done: (() -> Unit)? = null, block: () -> Boolean) {
        executor.execute {
            val changed = try {
                block()
            } catch (e: Exception) {
                PacketLogger.error("usage write", e)
                false
            } finally {
                done?.invoke()
            }
            if (changed) main.post { listeners.forEach { it() } }
        }
    }

    /**
     * The device's id (its address), Bluetooth name, model name when `models.json` knows it, and its kind
     * ([DeviceCatalog.kindOf]: Bluetooth class, series, name words).
     */
    @SuppressLint("MissingPermission")
    private fun describe(context: Context, device: BluetoothDevice): UsageDevice {
        val name = runCatching { device.name }.getOrNull()
        val p = prefs(context)
        // The model detected from the product id belongs to the buds whose name was saved at connect time.
        val model = if (name != null && p.getString(ModelCatalog.KEY_DEVICE_NAME, null) == name) ModelCatalog.current(context)?.name
            else ModelCatalog.find(ModelCatalog.all(context), null, name)?.name
        val kind = DeviceCatalog.kindOf(name, model, speakerClass = AudioDevice.isSpeaker(device))
        return UsageDevice(device.address, name, model, kind?.name)
    }

    /** A kind learned later than the connect: Fast Pair reporting a case means TWS. */
    fun saveKind(context: Context, deviceId: String, kind: DeviceCatalog.Kind) = write(context) {
        val s = store(context)
        val d = s.devices().firstOrNull { it.id == deviceId } ?: return@write false
        if (d.kind == kind.name) return@write false
        s.upsertDevice(d.copy(kind = kind.name))
        remembered.remove(deviceId)
        true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
}
