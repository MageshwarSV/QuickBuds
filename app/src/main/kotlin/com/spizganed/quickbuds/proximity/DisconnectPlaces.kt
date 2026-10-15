package com.spizganed.quickbuds.proximity

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.location.LocationManagerCompat
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.ui.ThemeRes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One disconnect: which device, when, and where the phone was. Two positions, never mixed up:
 *  - [lat]/[lng]: the phone measured right AFTER the disconnect (null when none was found; [note] says why);
 *  - [connected]: the phone's last position WHILE still connected ([LocationBuffer]), often nearer the buds.
 * Neither is where the buds are: the buds report no position. [place] is the street address of [target].
 */
data class DisconnectPlace(
    val atMs: Long,
    val address: String,
    val name: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Float? = null,
    val fixAtMs: Long? = null,
    val note: Note = Note.FIX,
    val place: String? = null,
    val connected: Fix? = null
) {
    enum class Note { FIX, LAST_KNOWN, LOCATION_OFF, NO_PERMISSION, NOT_FOUND }

    val hasPosition get() = (lat != null && lng != null) || connected != null

    /** The position after the disconnect, as a [Fix], or null. */
    val after: Fix? get() = if (lat != null && lng != null) Fix(lat, lng, accuracyM, fixAtMs ?: atMs) else null

    /** Where to go: the position while connected if it is good or fair, else the one after, else whatever there is. */
    val target: Fix? get() {
        val c = connected
        if (c != null && DisconnectPlaceRules.confidence(c, atMs) <= DisconnectPlaceRules.Confidence.FAIR) return c
        return after ?: c
    }
}

/** A position from Android, without the Android class, so the choice of the best one is testable. */
data class Fix(val lat: Double, val lng: Double, val accuracyM: Float?, val atMs: Long)

/** Pure rules: the history list and the choice of a fallback position. */
object DisconnectPlaceRules {
    /** The history keeps this many, newest first. */
    const val MAX = 200

    /** Both links of one device (classic and LE) drop within seconds: one entry. */
    const val SAME_DISCONNECT_MS = 30_000L

    /** A last known position older than this is too old to say where the phone was. */
    const val LAST_KNOWN_MAX_AGE_MS = 10 * 60_000L

    /** [entry] first, then the list, capped at [MAX]. Null when the same device just got an entry. */
    fun add(list: List<DisconnectPlace>, entry: DisconnectPlace): List<DisconnectPlace>? {
        val repeat = list.any { it.address == entry.address && kotlin.math.abs(it.atMs - entry.atMs) < SAME_DISCONNECT_MS }
        if (repeat) return null
        return (listOf(entry) + list).take(MAX)
    }

    /** How far to trust a position for a disconnect at [atMs]: its accuracy and how old it was then. */
    enum class Confidence { GOOD, FAIR, POOR, STALE }

    /** A position older than this at the disconnect says little about where it happened. */
    const val STALE_MS = 15 * 60_000L

    fun confidence(f: Fix, atMs: Long): Confidence {
        val age = atMs - f.atMs
        val acc = f.accuracyM
        return when {
            age > STALE_MS -> Confidence.STALE
            acc == null || acc > 100f -> Confidence.POOR
            acc <= 25f && age <= 2 * 60_000L -> Confidence.GOOD
            age <= 10 * 60_000L -> Confidence.FAIR
            else -> Confidence.POOR
        }
    }

    /** The newest fix not older than [LAST_KNOWN_MAX_AGE_MS]; among equals in age, the most accurate. */
    fun bestLastKnown(fixes: List<Fix>, nowMs: Long): Fix? = fixes
        .filter { nowMs - it.atMs in 0..LAST_KNOWN_MAX_AGE_MS }
        .sortedWith(compareByDescending<Fix> { it.atMs / 60_000L }.thenBy { it.accuracyM ?: Float.MAX_VALUE })
        .firstOrNull()
}

/**
 * Disconnect locations: when earbuds or another audio device disconnect, the phone's position is saved, so the
 * user can find the place again. Off by default; it needs location permission ("all the time" to work with the app
 * closed). The history stays on the phone (a JSON file in app storage).
 */
object DisconnectPlaces {

    const val KEY_ENABLED = "disconnectPlaces"
    private const val FILE = "disconnect_places.json"
    private const val CURRENT_TIMEOUT_MS = 20_000L

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false)

    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()

    fun foregroundPermitted(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Android 10+: without "Allow all the time", nothing is found while the app is closed. */
    fun backgroundPermitted(c: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    // --- Storage ---

    private val lock = Any()

    private fun file(c: Context) = File(c.filesDir, FILE)

    fun all(c: Context): List<DisconnectPlace> = synchronized(lock) {
        runCatching {
            val f = file(c)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
        }.onFailure { PacketLogger.error("disconnect places read", it) }.getOrDefault(emptyList())
    }

    private fun write(c: Context, list: List<DisconnectPlace>) = synchronized(lock) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(toJson(it)) }
            val f = file(c)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { PacketLogger.error("disconnect places write", it) }
    }

    /** Adds [entry] unless the same device just got one. */
    private fun add(c: Context, entry: DisconnectPlace) = synchronized(lock) {
        val next = DisconnectPlaceRules.add(all(c), entry) ?: return@synchronized
        write(c, next)
        PacketLogger.log("PLACE: ${entry.name} ${entry.note} ${if (entry.hasPosition) "±${entry.accuracyM?.toInt()} m" else ""}")
    }

    /** The street address the screen found for one entry. */
    fun setPlace(c: Context, atMs: Long, address: String, place: String) = synchronized(lock) {
        write(c, all(c).map { if (it.atMs == atMs && it.address == address) it.copy(place = place) else it })
    }

    fun clear(c: Context) = synchronized(lock) { file(c).delete() }

    private fun toJson(p: DisconnectPlace) = JSONObject().apply {
        put("at", p.atMs); put("address", p.address); put("name", p.name); put("note", p.note.name)
        p.lat?.let { put("lat", it) }; p.lng?.let { put("lng", it) }
        p.accuracyM?.let { put("acc", it.toDouble()) }; p.fixAtMs?.let { put("fixAt", it) }
        p.place?.let { put("place", it) }
        p.connected?.let { c ->
            put("cLat", c.lat); put("cLng", c.lng); put("cAt", c.atMs); c.accuracyM?.let { put("cAcc", it.toDouble()) }
        }
    }

    private fun fromJson(o: JSONObject) = runCatching {
        DisconnectPlace(
            atMs = o.getLong("at"),
            address = o.getString("address"),
            name = o.optString("name", o.getString("address")),
            lat = if (o.has("lat")) o.getDouble("lat") else null,
            lng = if (o.has("lng")) o.getDouble("lng") else null,
            accuracyM = if (o.has("acc")) o.getDouble("acc").toFloat() else null,
            fixAtMs = if (o.has("fixAt")) o.getLong("fixAt") else null,
            note = runCatching { DisconnectPlace.Note.valueOf(o.getString("note")) }.getOrDefault(DisconnectPlace.Note.FIX),
            place = o.optString("place").takeIf { it.isNotEmpty() },
            connected = if (o.has("cLat")) Fix(o.getDouble("cLat"), o.getDouble("cLng"),
                if (o.has("cAcc")) o.getDouble("cAcc").toFloat() else null, o.getLong("cAt")) else null
        )
    }.getOrNull()

    // --- Recording ---

    /**
     * A device disconnected: saves where the phone is. Asks Android for a current position (at most 20 s), else
     * takes a recent last known one. [done] runs once the entry is saved (or skipped: the feature is off).
     */
    @SuppressLint("MissingPermission")
    fun record(context: Context, address: String, name: String, done: () -> Unit) {
        val c = context.applicationContext
        if (!enabled(c)) return done()
        val now = System.currentTimeMillis()
        // The phone's last position while still connected, if one is recent enough to mean something.
        val whileConnected = LocationBuffer.latest(c)?.first?.takeIf { now - it.atMs <= DisconnectPlaceRules.STALE_MS }
        fun save(entry: DisconnectPlace) { add(c, entry.copy(connected = whileConnected)); done() }
        if (!foregroundPermitted(c)) return save(DisconnectPlace(now, address, name, note = DisconnectPlace.Note.NO_PERMISSION))
        val lm = c.getSystemService(LocationManager::class.java)
        if (lm == null || !LocationManagerCompat.isLocationEnabled(lm)) {
            return save(DisconnectPlace(now, address, name, note = DisconnectPlace.Note.LOCATION_OFF))
        }
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val lastKnown = DisconnectPlaceRules.bestLastKnown(providers.mapNotNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { Fix(it.latitude, it.longitude, accuracy(it), it.time) }
        }, now)
        fun fallback() = save(
            if (lastKnown != null) DisconnectPlace(now, address, name, lastKnown.lat, lastKnown.lng, lastKnown.accuracyM,
                lastKnown.atMs, DisconnectPlace.Note.LAST_KNOWN)
            else DisconnectPlace(now, address, name, note = DisconnectPlace.Note.NOT_FOUND)
        )
        // Fused (Android 12+) mixes GPS, Wi-Fi and cell; else the network is fast indoors, GPS last.
        val provider = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null,
            LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER
        ).firstOrNull { it in providers } ?: return fallback()
        val main = Handler(Looper.getMainLooper())
        val cancel = CancellationSignal()
        var finished = false
        fun finish(loc: Location?) {
            if (finished) return
            finished = true
            if (loc == null) return fallback()
            save(DisconnectPlace(now, address, name, loc.latitude, loc.longitude, accuracy(loc), loc.time, DisconnectPlace.Note.FIX))
        }
        main.postDelayed({ if (!finished) { cancel.cancel(); finish(null) } }, CURRENT_TIMEOUT_MS)
        runCatching {
            LocationManagerCompat.getCurrentLocation(lm, provider, cancel, { main.post(it) }) { loc -> main.post { finish(loc) } }
        }.onFailure {
            PacketLogger.error("disconnect place", it)
            main.post { finish(null) }
        }
    }

    private fun accuracy(l: Location) = if (l.hasAccuracy()) l.accuracy else null
}
