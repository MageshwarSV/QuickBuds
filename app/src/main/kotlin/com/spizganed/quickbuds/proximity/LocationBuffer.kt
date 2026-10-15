package com.spizganed.quickbuds.proximity

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * The phone's recent positions while earbuds are connected, so a disconnect can be placed where the phone WAS
 * while still connected, not only where it is a moment later. Runs only while Disconnect locations is on, location
 * is allowed and a device is connected.
 *
 * Battery: the passive provider (positions other apps asked for, free) and one low-power request (Wi-Fi and cell,
 * at most every 60 s, only after 10 m of movement). No GPS of its own. The newest fix is also kept in the
 * preferences, so a disconnect after the process was killed still finds it.
 */
object LocationBuffer {

    private const val KEY_LAT = "locBufLat"
    private const val KEY_LNG = "locBufLng"
    private const val KEY_ACC = "locBufAcc"
    private const val KEY_AT = "locBufAt"
    private const val KEY_PROVIDER = "locBufProvider"
    private const val INTERVAL_MS = 60_000L
    private const val MIN_DISTANCE_M = 10f

    private var running = false
    private var latest: Fix? = null
    private var latestProvider: String? = null

    private val listener = LocationListenerCompat { onFix(it) }
    private val passive = LocationListener { onFix(it) }

    private var app: Context? = null

    private fun onFix(l: Location) {
        val f = Fix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null, l.time)
        val now = latest
        // Keep the better of two fixes from the same minute; else the newer one.
        if (now != null && f.atMs < now.atMs) return
        if (now != null && f.atMs - now.atMs < 60_000L && (f.accuracyM ?: Float.MAX_VALUE) > (now.accuracyM ?: Float.MAX_VALUE)) return
        latest = f
        latestProvider = l.provider
        app?.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putString(KEY_LAT, f.lat.toString())?.putString(KEY_LNG, f.lng.toString())
            ?.putFloat(KEY_ACC, f.accuracyM ?: -1f)?.putLong(KEY_AT, f.atMs)?.putString(KEY_PROVIDER, l.provider)?.apply()
    }

    /** Starts or stops to match [anyConnected] and the Disconnect locations setting. */
    @SuppressLint("MissingPermission")
    fun sync(context: Context, anyConnected: Boolean) {
        val c = context.applicationContext
        app = c
        val want = anyConnected && DisconnectPlaces.enabled(c) && DisconnectPlaces.foregroundPermitted(c)
        if (want == running) return
        val lm = c.getSystemService(LocationManager::class.java) ?: return
        if (want) {
            runCatching {
                if (LocationManager.PASSIVE_PROVIDER in lm.allProviders) {
                    lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, passive, Looper.getMainLooper())
                }
                val provider = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> null
                }
                if (provider != null) LocationManagerCompat.requestLocationUpdates(lm, provider,
                    LocationRequestCompat.Builder(INTERVAL_MS).setQuality(LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY)
                        .setMinUpdateDistanceMeters(MIN_DISTANCE_M).build(), listener, Looper.getMainLooper())
                running = true
                PacketLogger.log("PLACE: recent positions on (${provider ?: "passive only"})")
            }.onFailure { PacketLogger.error("location buffer", it) }
        } else {
            runCatching { lm.removeUpdates(passive) }
            runCatching { LocationManagerCompat.removeUpdates(lm, listener) }
            running = false
            PacketLogger.log("PLACE: recent positions off")
        }
    }

    /** The newest fix seen, from memory or (after a restart) the preferences, with its provider. */
    fun latest(context: Context): Pair<Fix, String?>? {
        latest?.let { return it to latestProvider }
        val p = context.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
        val at = p.getLong(KEY_AT, 0L).takeIf { it > 0 } ?: return null
        val lat = p.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lng = p.getString(KEY_LNG, null)?.toDoubleOrNull() ?: return null
        return Fix(lat, lng, p.getFloat(KEY_ACC, -1f).takeIf { it >= 0 }, at) to p.getString(KEY_PROVIDER, null)
    }
}
