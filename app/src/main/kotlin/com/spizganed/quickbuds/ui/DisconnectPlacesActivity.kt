package com.spizganed.quickbuds.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.Gravity
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.proximity.DisconnectPlace
import com.spizganed.quickbuds.proximity.DisconnectPlaces
import java.util.Locale

/**
 * Disconnect locations: the switch, then every saved disconnect, newest first: device, time, street address (or
 * coordinates), accuracy. A tap opens the place in a maps app. Addresses are looked up here, not at the disconnect,
 * so recording stays short.
 */
class DisconnectPlacesActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var recordSwitch: Switch
    private lateinit var recordSub: TextView
    /** Entries an address was already looked up for, found or not: one try per screen visit. */
    private val tried = HashSet<Long>()
    private var geocoding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.dloc_title))

        recordSwitch = SettingRowFactory.buildSwitch(this, DisconnectPlaces.enabled(this) && DisconnectPlaces.foregroundPermitted(this))
        val row = SettingRowFactory.build(this, R.drawable.ic_place, R.string.dloc_record_title, 0, recordSwitch) {
            recordSwitch.performClick()
        }
        recordSub = SettingRowFactory.subtitle(this, row)
        recordSwitch.setOnCheckedChangeListener(recordListener)
        root.addView(SettingRowFactory.card(this).apply { addView(row) })

        root.addView(SettingRowFactory.sectionLabel(this, R.string.dloc_history))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.color(this@DisconnectPlacesActivity, R.attr.appColorBg))
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        // Permission may have changed in Android settings while away.
        quietSwitch(DisconnectPlaces.enabled(this) && DisconnectPlaces.foregroundPermitted(this))
        paintSwitch()
        render()
    }

    // --- The switch and its permissions ---

    private val recordListener = CompoundButton.OnCheckedChangeListener { _, on ->
        if (on && !DisconnectPlaces.foregroundPermitted(this)) {
            quietSwitch(false)
            ConfirmDialog.show(this, getString(R.string.dloc_perm_title), getString(R.string.dloc_perm_body),
                getString(R.string.dloc_continue)) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_FOREGROUND)
            }
            return@OnCheckedChangeListener
        }
        DisconnectPlaces.setEnabled(this, on)
        if (on) askBackground()
        paintSwitch()
    }

    private fun quietSwitch(on: Boolean) {
        recordSwitch.setOnCheckedChangeListener(null)
        recordSwitch.isChecked = on
        recordSwitch.setOnCheckedChangeListener(recordListener)
    }

    /** Android 10+: "Allow all the time" lets it record with the app closed. Asked once per turn-on. */
    private fun askBackground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || DisconnectPlaces.backgroundPermitted(this)) return
        ConfirmDialog.show(this, getString(R.string.dloc_bg_title), getString(R.string.dloc_bg_body), getString(R.string.dloc_continue)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_BACKGROUND)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_FOREGROUND -> if (DisconnectPlaces.foregroundPermitted(this)) {
                DisconnectPlaces.setEnabled(this, true)
                quietSwitch(true)
                askBackground()
            } else {
                // Refused for good: Android shows no dialog any more, so offer the app's settings.
                ConfirmDialog.show(this, getString(R.string.dloc_perm_title), getString(R.string.dloc_perm_body),
                    getString(R.string.perm_action), cancelRes = R.string.dialog_close) { openAppSettings() }
            }
        }
        paintSwitch()
    }

    private fun openAppSettings() = runCatching {
        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun paintSwitch() {
        val on = DisconnectPlaces.enabled(this) && DisconnectPlaces.foregroundPermitted(this)
        recordSub.text = getString(when {
            !on -> R.string.dloc_record_off
            !DisconnectPlaces.backgroundPermitted(this) -> R.string.dloc_needs_background
            else -> R.string.dloc_record_on
        })
    }

    // --- The history ---

    private fun render() {
        list.removeAllViews()
        val entries = DisconnectPlaces.all(this)
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                setText(R.string.dloc_empty)
                setTextColor(ThemeRes.color(this@DisconnectPlacesActivity, R.attr.appColorTextSecondary))
                textSize = 14f
                gravity = Gravity.CENTER
                val dp = { v: Float -> ThemeRes.dp(this@DisconnectPlacesActivity, v) }
                setPadding(dp(16f), dp(24f), dp(16f), dp(24f))
            })
            return
        }
        val card = SettingRowFactory.card(this)
        entries.forEach { SettingRowFactory.addRow(card, entryRow(it)) }
        list.addView(card)
        val clear = SettingRowFactory.build(this, R.drawable.ic_delete, R.string.dloc_clear, 0, null) {
            ConfirmDialog.show(this, getString(R.string.dloc_clear_title), getString(R.string.dloc_clear_body),
                getString(R.string.dloc_clear)) {
                DisconnectPlaces.clear(this)
                render()
            }
        }
        list.addView(SettingRowFactory.card(this).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = ThemeRes.dp(this@DisconnectPlacesActivity, 12f)
            addView(clear)
        })
        lookUpAddresses(entries)
    }

    private fun entryRow(p: DisconnectPlace): LinearLayout {
        val chevron = if (p.hasPosition) SettingRowFactory.buildChevron(this) else null
        val row = SettingRowFactory.build(this, R.drawable.ic_place, 0, 0, chevron,
            onClick = if (p.hasPosition) ({ openMap(p) }) else null)
        row.findViewWithTag<TextView>(SettingRowFactory.TITLE_TAG).text = p.name
        val time = DateUtils.formatDateTime(this, p.atMs,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL)
        val where = when (p.note) {
            DisconnectPlace.Note.LOCATION_OFF -> getString(R.string.dloc_note_off)
            DisconnectPlace.Note.NO_PERMISSION -> getString(R.string.dloc_note_permission)
            DisconnectPlace.Note.NOT_FOUND -> getString(R.string.dloc_note_not_found)
            DisconnectPlace.Note.FIX, DisconnectPlace.Note.LAST_KNOWN -> listOfNotNull(
                p.place ?: coordinates(p),
                p.accuracyM?.let { getString(R.string.dloc_accuracy, it.toInt()) },
                if (p.note == DisconnectPlace.Note.LAST_KNOWN) getString(R.string.dloc_last_known) else null
            ).joinToString(" · ")
        }
        SettingRowFactory.subtitle(this, row).text = "$time\n$where"
        return row
    }

    private fun coordinates(p: DisconnectPlace) = String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng)

    /** The maps app at the place, with a pin named after the device; a browser map when no maps app answers geo:. */
    private fun openMap(p: DisconnectPlace) {
        val ll = String.format(Locale.US, "%.6f,%.6f", p.lat, p.lng)
        val geo = Uri.parse("geo:$ll?q=$ll(${Uri.encode(p.name)})")
        val web = Uri.parse("https://www.google.com/maps/search/?api=1&query=$ll")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, geo))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, web))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, R.string.dloc_no_maps, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Street addresses for entries that have none yet, off the main thread; the list redraws once they are in. */
    private fun lookUpAddresses(entries: List<DisconnectPlace>) {
        if (geocoding || !Geocoder.isPresent()) return
        val todo = entries.filter { it.hasPosition && it.place == null && it.atMs !in tried }.take(20)
        if (todo.isEmpty()) return
        geocoding = true
        todo.forEach { tried.add(it.atMs) }
        val app = applicationContext
        Thread {
            var found = 0
            val geocoder = Geocoder(app)
            for (p in todo) {
                val line = runCatching {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(p.lat!!, p.lng!!, 1)?.firstOrNull()?.getAddressLine(0)
                }.onFailure { PacketLogger.log("PLACE: address lookup failed: ${it.message}") }.getOrNull()
                if (!line.isNullOrBlank()) {
                    DisconnectPlaces.setPlace(app, p.atMs, p.address, line)
                    found++
                }
            }
            main.post {
                geocoding = false
                if (found > 0 && !isFinishing && !isDestroyed) render()
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        private const val REQUEST_FOREGROUND = 71
        private const val REQUEST_BACKGROUND = 72
    }
}
