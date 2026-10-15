package com.spizganed.quickbuds.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.proximity.DisconnectPlace
import com.spizganed.quickbuds.proximity.DisconnectPlaceRules
import com.spizganed.quickbuds.proximity.DisconnectPlaces
import com.spizganed.quickbuds.proximity.PlaceNavigation
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Back to a saved disconnect place: an arrow that turns with the phone, the straight-line distance, and how far to
 * trust it. GPS only brings the user to the area: within the margin it says so ("near"), never "0 m"; then
 * Search nearby (the buds' own Bluetooth signal) takes over. The saved spot is where the PHONE was.
 *
 * Heading: the rotation vector (gyroscope + magnetometer + accelerometer, fused by Android), turned to true north
 * with the magnetic declination at the phone. A low compass accuracy shows a warning and a dashed arrow.
 */
class PlaceNavigatorActivity : Activity(), SensorEventListener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var place: DisconnectPlace
    private lateinit var arrow: ArrowView
    private lateinit var distance: TextView
    private lateinit var status: TextView
    private lateinit var yours: TextView
    private lateinit var margin: TextView
    private lateinit var compassNote: TextView

    private var fix: Location? = null
    private var fixAtElapsed = 0L
    /** Smoothed heading as a unit vector (no jump across north). */
    private var hx = 0.0
    private var hy = 0.0
    private var hasHeading = false
    private var compassAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    private var declination = 0f

    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val rotation by lazy {
        sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sensors?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    }
    private val magnetic by lazy { sensors?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) }
    private val locations by lazy { getSystemService(LocationManager::class.java) }
    private val locationListener = LocationListenerCompat { onFix(it) }
    private val tick = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 1_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val at = intent.getLongExtra(EXTRA_AT, 0L)
        val address = intent.getStringExtra(EXTRA_ADDRESS)
        place = DisconnectPlaces.all(this).firstOrNull { it.atMs == at && it.address == address } ?: return finish()
        val target = place.target ?: return finish()

        fun dp(v: Float) = ThemeRes.dp(this, v)
        fun text(size: Float, secondary: Boolean = true) = TextView(this).apply {
            textSize = size; gravity = Gravity.CENTER
            setTextColor(ThemeRes.color(this@PlaceNavigatorActivity, if (secondary) R.attr.appColorTextSecondary else R.attr.appColorTextPrimary))
        }
        fun lp(top: Float) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }
        fun button(label: Int, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            setText(label); textSize = 16f; typeface = ThemeRes.medium(context); gravity = Gravity.CENTER
            val p = ThemeRes.palette(this@PlaceNavigatorActivity)
            setTextColor(if (primary) ThemeRes.onFill(this@PlaceNavigatorActivity) else p.accent)
            background = ThemeRes.ripple(this@PlaceNavigatorActivity,
                if (primary) ThemeRes.pill(this@PlaceNavigatorActivity, p.accent, 26f) else ThemeRes.card(this@PlaceNavigatorActivity, 26f))
            setOnClickListener { Haptics.commit(this); onClick() }
        }

        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.nav_title))
        val time = DateUtils.formatDateTime(this, place.atMs,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL)
        root.addView(text(16f, secondary = false).apply { this.text = place.name; gravity = Gravity.START }, lp(4f))
        root.addView(text(13f).apply {
            gravity = Gravity.START
            this.text = getString(R.string.nav_disconnected_at, time) + "\n" + targetLine(target) + "\n" + getString(R.string.nav_phone_not_buds)
        }, lp(2f))

        arrow = ArrowView(this)
        root.addView(arrow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220f)).apply { topMargin = dp(16f) })
        distance = text(44f, secondary = false).apply { typeface = ThemeRes.headline(this@PlaceNavigatorActivity) }
        root.addView(distance, lp(8f))
        status = text(15f, secondary = false)
        root.addView(status, lp(4f))
        compassNote = text(13f).apply { setTextColor(ThemeRes.palette(this@PlaceNavigatorActivity).accent) }
        root.addView(compassNote, lp(6f))
        yours = text(13f)
        root.addView(yours, lp(12f))
        root.addView(text(13f).apply {
            this.text = target.accuracyM?.let { getString(R.string.nav_saved_fix, it.roundToInt()) } ?: getString(R.string.nav_saved_fix_unknown)
        }, lp(2f))
        margin = text(13f)
        root.addView(margin, lp(2f))
        // A saved place from Wi-Fi or the mobile network: say so, so its distance is not read as the buds' distance.
        target.accuracyM?.takeIf { it > PlaceNavigation.ROUGH_M }?.let { acc ->
            root.addView(text(13f).apply {
                setTextColor(ThemeRes.palette(this@PlaceNavigatorActivity).accent)
                this.text = getString(R.string.nav_rough, acc.roundToInt())
            }, lp(8f))
        }

        root.addView(button(R.string.nav_search_nearby, true) {
            startActivity(FindNearbyActivity.intent(this, place.address, place.name))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52f)).apply { topMargin = dp(20f) })
        root.addView(button(R.string.nav_open_map, false) { openMap(target.lat, target.lng) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52f)).apply { topMargin = dp(10f) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.color(this@PlaceNavigatorActivity, R.attr.appColorBg))
            isFillViewport = true
            addView(root)
        })
    }

    private fun targetLine(t: com.spizganed.quickbuds.proximity.Fix): String {
        val which = getString(if (t == place.connected) R.string.nav_target_connected else R.string.nav_target_after)
        val conf = getString(when (DisconnectPlaceRules.confidence(t, place.atMs)) {
            DisconnectPlaceRules.Confidence.GOOD -> R.string.nav_conf_good
            DisconnectPlaceRules.Confidence.FAIR -> R.string.nav_conf_fair
            DisconnectPlaceRules.Confidence.POOR -> R.string.nav_conf_poor
            DisconnectPlaceRules.Confidence.STALE -> R.string.nav_conf_stale
        })
        return "$which · $conf"
    }

    override fun onResume() {
        super.onResume()
        rotation?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magnetic?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        startLocation()
        main.post(tick)
    }

    // Stopping updates needs no check: without the permission nothing was started, and runCatching covers the rest.
    @SuppressLint("MissingPermission")
    override fun onPause() {
        sensors?.unregisterListener(this)
        runCatching { locations?.let { LocationManagerCompat.removeUpdates(it, locationListener) } }
        main.removeCallbacks(tick)
        super.onPause()
    }

    /** Fine updates every second while this screen is open, and only then. */
    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!DisconnectPlaces.foregroundPermitted(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
            return
        }
        val lm = locations ?: return
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        } ?: return
        runCatching {
            LocationManagerCompat.requestLocationUpdates(lm, provider,
                LocationRequestCompat.Builder(1_000L).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).build(),
                locationListener, Looper.getMainLooper())
        }.onFailure { PacketLogger.error("place navigation location", it) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION && DisconnectPlaces.foregroundPermitted(this)) startLocation()
    }

    private fun onFix(l: Location) {
        fix = l
        fixAtElapsed = SystemClock.elapsedRealtime()
        declination = GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), System.currentTimeMillis()).declination
        render()
    }

    private val matrix = FloatArray(9)
    private val angles = FloatArray(3)

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) return
        SensorManager.getRotationMatrixFromVector(matrix, e.values)
        SensorManager.getOrientation(matrix, angles)
        val az = Math.toRadians(Math.toDegrees(angles[0].toDouble()) + declination)
        // Low-pass on the unit vector, so 359° to 1° does not swing the arrow round.
        if (!hasHeading) { hx = cos(az); hy = sin(az); hasHeading = true }
        else { hx += (cos(az) - hx) * 0.15; hy += (sin(az) - hy) * 0.15 }
        renderArrow()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD || sensor == rotation) compassAccuracy = accuracy
    }

    private fun headingDeg() = PlaceNavigation.normalize(Math.toDegrees(atan2(hy, hx)))

    private fun compassOk() = rotation != null && hasHeading && compassAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM

    private fun renderArrow() {
        val t = place.target ?: return
        val f = fix ?: return arrow.show(null, false, false)
        val bearing = PlaceNavigation.bearingDeg(f.latitude, f.longitude, t.lat, t.lng)
        val dist = PlaceNavigation.distanceM(f.latitude, f.longitude, t.lat, t.lng)
        val unc = PlaceNavigation.uncertaintyM(if (f.hasAccuracy()) f.accuracy else null, t.accuracyM)
        val near = PlaceNavigation.state(dist, unc) == PlaceNavigation.State.NEAR
        arrow.show(if (hasHeading) PlaceNavigation.relativeDeg(bearing, headingDeg()).toFloat() else null, compassOk(), near)
    }

    private fun render() {
        val t = place.target ?: return
        val f = fix
        compassNote.text = when {
            rotation == null -> getString(R.string.nav_no_compass)
            hasHeading && !compassOk() -> getString(R.string.nav_compass_bad)
            else -> ""
        }
        if (f == null) {
            distance.text = "—"
            status.setText(R.string.nav_waiting_fix)
            yours.text = ""; margin.text = ""
            arrow.show(null, false, false)
            return
        }
        val dist = PlaceNavigation.distanceM(f.latitude, f.longitude, t.lat, t.lng)
        val acc = if (f.hasAccuracy()) f.accuracy else null
        val unc = PlaceNavigation.uncertaintyM(acc, t.accuracyM)
        val near = PlaceNavigation.state(dist, unc) == PlaceNavigation.State.NEAR
        val (km, value) = PlaceNavigation.splitDistance(dist)
        distance.text = if (near) getString(R.string.nav_near) else getString(if (km) R.string.nav_km else R.string.nav_m, value)
        status.setText(if (near) R.string.nav_near_hint else R.string.nav_go_hint)
        val ageS = ((SystemClock.elapsedRealtime() - fixAtElapsed) / 1000).toInt()
        val age = if (ageS < 60) getString(R.string.nav_age_s, ageS) else UsageFormat.duration(this, ageS * 1000L)
        yours.text = acc?.let { getString(R.string.nav_your_fix, it.roundToInt(), age) } ?: getString(R.string.nav_your_fix_unknown, age)
        margin.text = unc?.let { getString(R.string.nav_margin, it.roundToInt()) } ?: ""
        if (rotation == null) compassNote.text = getString(R.string.nav_no_compass_bearing,
            PlaceNavigation.bearingDeg(f.latitude, f.longitude, t.lat, t.lng).roundToInt())
        renderArrow()
    }

    private fun openMap(lat: Double, lng: Double) {
        val ll = String.format(java.util.Locale.US, "%.6f,%.6f", lat, lng)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:$ll?q=$ll(${android.net.Uri.encode(place.name)})"))) }
            .recoverCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/maps/search/?api=1&query=$ll"))) }
            .onFailure { android.widget.Toast.makeText(this, R.string.dloc_no_maps, android.widget.Toast.LENGTH_SHORT).show() }
    }

    /** A big arrow at [angle] degrees (0 = ahead); dashed when the compass is unsure; a ring when near. */
    private class ArrowView(context: Context) : View(context) {
        private var angle: Float? = null
        private var sure = false
        private var near = false
        private val accent = ThemeRes.palette(context).accent
        private val muted = ThemeRes.color(context, R.attr.appColorTextSecondary)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ThemeRes.dp(context, 3f).toFloat() }
        private val path = Path()

        fun show(angle: Float?, sure: Boolean, near: Boolean) {
            this.angle = angle; this.sure = sure; this.near = near
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = minOf(width, height) * 0.42f
            if (near) {
                stroke.color = accent; stroke.pathEffect = null
                canvas.drawCircle(cx, cy, r, stroke)
                fill.color = accent
                canvas.drawCircle(cx, cy, r * 0.12f, fill)
                return
            }
            val a = angle
            if (a == null) {
                stroke.color = muted; stroke.pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
                canvas.drawCircle(cx, cy, r, stroke)
                return
            }
            canvas.save()
            canvas.rotate(a, cx, cy)
            path.reset()
            path.moveTo(cx, cy - r)
            path.lineTo(cx + r * 0.55f, cy + r * 0.55f)
            path.lineTo(cx, cy + r * 0.2f)
            path.lineTo(cx - r * 0.55f, cy + r * 0.55f)
            path.close()
            if (sure) {
                fill.color = accent
                canvas.drawPath(path, fill)
            } else {
                stroke.color = muted; stroke.pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
                canvas.drawPath(path, stroke)
            }
            canvas.restore()
        }
    }

    companion object {
        private const val EXTRA_AT = "at"
        private const val EXTRA_ADDRESS = "address"
        private const val REQUEST_LOCATION = 81

        fun intent(context: Context, p: DisconnectPlace): Intent =
            Intent(context, PlaceNavigatorActivity::class.java).putExtra(EXTRA_AT, p.atMs).putExtra(EXTRA_ADDRESS, p.address)
    }
}
