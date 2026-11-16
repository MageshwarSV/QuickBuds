package com.spizganed.quickbuds.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * Bike mode by itself (Bike mode screen, both off by default): on when riding faster than [SpeedRule.ON_KMH] for
 * a minute, or when a chosen Bluetooth device connects (a bike intercom, a car). Off again by the same thing: after
 * [SpeedRule.OFF_MS] below [SpeedRule.OFF_KMH], or when that device disconnects. Bike mode turned on by hand is
 * never turned off by itself.
 */
object BikeAuto {
    const val KEY_SPEED = "bikeAutoSpeed"
    const val KEY_DEVICES = "bikeAutoDevices"
    const val BY_SPEED = "speed"
    fun byDevice(address: String) = "bt:$address"

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
    private fun log(msg: String) = PacketLogger.log("BIKE AUTO: $msg")

    fun speedOn(c: Context) = prefs(c).getBoolean(KEY_SPEED, false)
    fun setSpeed(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_SPEED, on).apply()
    fun devices(c: Context): Set<String> = prefs(c).getStringSet(KEY_DEVICES, emptySet()).orEmpty()
    fun setDevices(c: Context, addresses: Set<String>) = prefs(c).edit().putStringSet(KEY_DEVICES, addresses.toSet()).apply()

    /** Speed needs the location all the time: the phone sits in a pocket, the screen off. */
    fun locationPermitted(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    /** A Bluetooth device connected or went ([com.spizganed.quickbuds.bluetooth.KeepAliveReceiver]). */
    fun onDevice(c: Context, address: String, connected: Boolean) {
        if (address !in devices(c)) return
        if (connected) {
            if (BikeMode.isOn(c)) return
            if (BikeMode.set(c, true, byDevice(address))) log("on: a chosen device connected")
            else log("not on: the chosen features need permissions (open Bike mode)")
        } else if (BikeMode.by(c) == byDevice(address)) {
            BikeMode.set(c, false)
            log("off: the chosen device disconnected")
        }
    }
}

/** Speed samples: riding or stopped. Pure. */
object SpeedRule {
    data class Sample(val atMs: Long, val kmh: Float)

    const val ON_KMH = 30f
    const val ON_MS = 60_000L
    /** Of the samples of [ON_MS], this share at [ON_KMH] or more: a traffic light or a GPS slip does not reset it. */
    const val ON_SHARE = 0.8
    const val MIN_SAMPLES = 6
    const val OFF_KMH = 10f
    const val OFF_MS = 10 * 60_000L
    /** Above this, the phone is not on a bike or in a car (a GPS jump). */
    const val MAX_KMH = 250f

    /** Riding: the samples of the last [ON_MS] reach back that far, and most are fast. */
    fun riding(samples: List<Sample>, nowMs: Long): Boolean {
        val w = samples.filter { nowMs - it.atMs <= ON_MS && it.kmh <= MAX_KMH }
        if (w.size < MIN_SAMPLES || nowMs - w.first().atMs < ON_MS - 10_000L) return false
        return w.count { it.kmh >= ON_KMH } >= w.size * ON_SHARE
    }

    /**
     * Stopped: [OFF_MS] with every sample slow, or no sample at all for that long (no GPS fix indoors: parked).
     * [sinceMs]: when riding was seen last; a ride shorter than [OFF_MS] is not stopped before.
     */
    fun stopped(samples: List<Sample>, nowMs: Long, sinceMs: Long): Boolean {
        if (nowMs - sinceMs < OFF_MS) return false
        val w = samples.filter { nowMs - it.atMs <= OFF_MS }
        return w.isEmpty() || w.all { it.kmh < OFF_KMH }
    }

    /** km/h from two fixes [m] metres and [ms] apart. */
    fun kmh(m: Float, ms: Long): Float = if (ms <= 0) 0f else m / (ms / 1000f) * 3.6f
}

/**
 * Watches the speed for [BikeAuto], in the background service, only while it may be needed: the option on, the
 * location allowed, and earbuds connected (or Bike mode on by speed, to see the stop). To spare the battery it
 * looks in three steps: the low-power location every 30 s; at a hint of [HINT_KMH], GPS every 2 s to confirm;
 * while riding, GPS every 15 s to see the stop. Main thread.
 */
class SpeedWatch(context: Context) {
    private val c = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lm = c.getSystemService(LocationManager::class.java)

    private enum class Step { OFF, WATCH, CONFIRM, RIDING }
    private var step = Step.OFF
    private val samples = ArrayDeque<SpeedRule.Sample>()
    private var lastFix: Location? = null
    /** When riding was seen last (elapsed time): the stop counts from then. */
    private var ridingSince = 0L

    private fun log(msg: String) = PacketLogger.log("BIKE AUTO: $msg")

    private val listener = LocationListenerCompat { onFix(it) }
    private val confirmTimeout = Runnable { if (step == Step.CONFIRM) { log("not riding fast enough"); go(Step.WATCH) } }
    /** While riding: the stop is seen also without new fixes (indoors). */
    private val stopCheck = object : Runnable {
        override fun run() {
            if (step != Step.RIDING) return
            checkStop()
            main.postDelayed(this, 60_000L)
        }
    }

    /** Starts, steps or stops to match the option, the permission, the earbuds and Bike mode. */
    fun sync(earbudsConnected: Boolean) {
        val bySpeed = BikeMode.by(c) == BikeAuto.BY_SPEED && BikeMode.isOn(c)
        val want = BikeAuto.speedOn(c) && BikeAuto.locationPermitted(c) && (earbudsConnected || bySpeed)
        when {
            !want -> go(Step.OFF)
            bySpeed && step != Step.RIDING -> go(Step.RIDING)
            // Bike mode turned off or taken over by hand while riding: back to watching.
            !bySpeed && step == Step.RIDING -> go(Step.WATCH)
            step == Step.OFF -> go(Step.WATCH)
        }
    }

    fun stop() = go(Step.OFF)

    @SuppressLint("MissingPermission")
    private fun go(next: Step) {
        if (next == step) return
        runCatching { lm?.let { LocationManagerCompat.removeUpdates(it, listener) } }
        main.removeCallbacks(confirmTimeout)
        main.removeCallbacks(stopCheck)
        samples.clear()
        lastFix = null
        step = next
        val m = lm ?: return
        if (next == Step.OFF) return log("speed watch off")
        val gps = m.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val (provider, interval, quality) = when (next) {
            Step.WATCH -> Triple(lowPower(m) ?: return log("no low-power location"), 30_000L, LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY)
            else -> Triple(if (gps) LocationManager.GPS_PROVIDER else return log("GPS is off"),
                if (next == Step.CONFIRM) 2_000L else 15_000L, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        }
        runCatching {
            LocationManagerCompat.requestLocationUpdates(m, provider,
                LocationRequestCompat.Builder(interval).setQuality(quality).setMinUpdateIntervalMillis(interval).build(),
                androidx.core.content.ContextCompat.getMainExecutor(c), listener)
        }.onFailure { return log("location refused: ${it.message}") }
        when (next) {
            Step.CONFIRM -> main.postDelayed(confirmTimeout, CONFIRM_MS)
            Step.RIDING -> { ridingSince = SystemClock.elapsedRealtime(); main.postDelayed(stopCheck, 60_000L) }
            else -> {}
        }
        log("speed watch: ${next.name.lowercase()} ($provider every ${interval / 1000} s)")
    }

    private fun lowPower(m: LocationManager): String? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && m.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
        m.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        m.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> null
    }

    private fun onFix(l: Location) {
        val now = SystemClock.elapsedRealtime()
        val prev = lastFix
        lastFix = l
        // GPS gives the speed; else from the last fix, when both are good enough to tell.
        val kmh = when {
            l.hasSpeed() && l.provider == LocationManager.GPS_PROVIDER -> l.speed * 3.6f
            prev != null && l.accuracy <= 100f && prev.accuracy <= 100f && l.time - prev.time >= 15_000L ->
                SpeedRule.kmh(prev.distanceTo(l), l.time - prev.time)
            else -> return
        }
        samples.addLast(SpeedRule.Sample(now, kmh))
        while (samples.isNotEmpty() && now - samples.first().atMs > SpeedRule.OFF_MS) samples.removeFirst()
        when (step) {
            Step.WATCH -> if (kmh >= HINT_KMH && kmh <= SpeedRule.MAX_KMH) { log("moving at about ${kmh.toInt()} km/h: confirming with GPS"); go(Step.CONFIRM) }
            Step.CONFIRM -> if (SpeedRule.riding(samples.toList(), now)) riding()
            Step.RIDING -> { if (kmh >= SpeedRule.OFF_KMH) ridingSince = now; checkStop() }
            Step.OFF -> {}
        }
    }

    private fun riding() {
        if (BikeMode.isOn(c)) { log("riding, Bike mode already on"); return go(Step.WATCH) }
        if (BikeMode.set(c, true, BikeAuto.BY_SPEED)) { log("on: riding faster than ${SpeedRule.ON_KMH.toInt()} km/h"); go(Step.RIDING) }
        else { log("not on: the chosen features need permissions (open Bike mode)"); go(Step.WATCH) }
    }

    private fun checkStop() {
        if (!SpeedRule.stopped(samples.toList(), SystemClock.elapsedRealtime(), ridingSince)) return
        if (BikeMode.by(c) == BikeAuto.BY_SPEED) { BikeMode.set(c, false); log("off: stopped for ${SpeedRule.OFF_MS / 60_000} min") }
        go(Step.WATCH)
    }

    companion object {
        /** A low-power fix this fast is worth a GPS look. */
        const val HINT_KMH = 20f
        /** GPS confirms within this long, or it goes back to watching. */
        const val CONFIRM_MS = 90_000L
    }
}
