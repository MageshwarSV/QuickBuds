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
        val export = SettingRowFactory.build(this, R.drawable.ic_share, R.string.dloc_export, R.string.dloc_export_sub, null) { export() }
        list.addView(SettingRowFactory.card(this).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = ThemeRes.dp(this@DisconnectPlacesActivity, 12f)
            SettingRowFactory.addRow(this, export)
            SettingRowFactory.addRow(this, clear)
        })
        lookUpAddresses(entries)
    }

    private fun entryRow(p: DisconnectPlace): LinearLayout {
        val chevron = if (p.hasPosition) SettingRowFactory.buildChevron(this) else null
        val row = SettingRowFactory.build(this, R.drawable.ic_place, 0, 0, chevron,
            onClick = if (p.hasPosition) ({ startActivity(PlaceNavigatorActivity.intent(this, p)) }) else null)
        row.findViewWithTag<TextView>(SettingRowFactory.TITLE_TAG).text = p.name
        val time = DateUtils.formatDateTime(this, p.atMs,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL)
        val t = p.target
        val where = if (t == null) when (p.note) {
            DisconnectPlace.Note.LOCATION_OFF -> getString(R.string.dloc_note_off)
            DisconnectPlace.Note.NO_PERMISSION -> getString(R.string.dloc_note_permission)
            else -> getString(R.string.dloc_note_not_found)
        } else listOfNotNull(
            p.place ?: String.format(Locale.US, "%.5f, %.5f", t.lat, t.lng),
            t.accuracyM?.let { getString(R.string.dloc_accuracy, it.toInt()) },
            getString(if (t == p.connected) R.string.dloc_while_connected else R.string.dloc_after),
            getString(when (com.spizganed.quickbuds.proximity.DisconnectPlaceRules.confidence(t, p.atMs)) {
                com.spizganed.quickbuds.proximity.DisconnectPlaceRules.Confidence.GOOD -> R.string.nav_conf_good
                com.spizganed.quickbuds.proximity.DisconnectPlaceRules.Confidence.FAIR -> R.string.nav_conf_fair
                com.spizganed.quickbuds.proximity.DisconnectPlaceRules.Confidence.POOR -> R.string.nav_conf_poor
                com.spizganed.quickbuds.proximity.DisconnectPlaceRules.Confidence.STALE -> R.string.nav_conf_stale
            }).lowercase(Locale.getDefault()),
            if (t != p.connected && p.note == DisconnectPlace.Note.LAST_KNOWN) getString(R.string.dloc_last_known) else null
        ).joinToString(" · ")
        SettingRowFactory.subtitle(this, row).text = "$time\n$where"
        return row
    }

    /**
     * The history as CSV, shared through the app's FileProvider: both positions per disconnect, with accuracy and
     * time. Written to the cache, so it does not stay on the phone after Android clears the cache.
     */
    private fun export() {
        val entries = DisconnectPlaces.all(this)
        if (entries.isEmpty()) return Toast.makeText(this, R.string.dloc_export_none, Toast.LENGTH_SHORT).show()
        val iso = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
        fun time(ms: Long?) = ms?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(iso) }.orEmpty()
        fun q(v: String?) = "\"" + (v ?: "").replace("\"", "\"\"") + "\""
        val csv = StringBuilder("disconnected,device,address,connected_lat,connected_lng,connected_accuracy_m,connected_time," +
            "after_lat,after_lng,after_accuracy_m,after_time,note,place\n")
        for (p in entries) {
            val c = p.connected
            csv.append(listOf(time(p.atMs), q(p.name), p.address, c?.lat ?: "", c?.lng ?: "", c?.accuracyM ?: "", time(c?.atMs),
                p.lat ?: "", p.lng ?: "", p.accuracyM ?: "", time(p.fixAtMs), p.note.name, q(p.place)).joinToString(",")).append('\n')
        }
        runCatching {
            val dir = java.io.File(cacheDir, "exports").apply { mkdirs() }
            val file = java.io.File(dir, "quickbuds-disconnect-locations.csv")
            file.writeText(csv.toString())
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.dloc_export)))
        }.onFailure { PacketLogger.error("disconnect places export", it) }
    }

    /** Street addresses for entries that have none yet, off the main thread; the list redraws once they are in. */
    private fun lookUpAddresses(entries: List<DisconnectPlace>) {
        if (geocoding || !Geocoder.isPresent()) return
        val todo = entries.filter { it.target != null && it.place == null && it.atMs !in tried }.take(20)
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
                    geocoder.getFromLocation(p.target!!.lat, p.target!!.lng, 1)?.firstOrNull()?.getAddressLine(0)
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
