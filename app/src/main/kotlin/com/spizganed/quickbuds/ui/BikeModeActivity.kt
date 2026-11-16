package com.spizganed.quickbuds.ui

import android.app.Activity
import android.os.Build
import android.bluetooth.BluetoothManager
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.CompoundButton
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.calls.AutoAnswer
import com.spizganed.quickbuds.calls.BikeMode
import com.spizganed.quickbuds.calls.BikeAuto
import com.spizganed.quickbuds.calls.VoiceAnswer
import com.spizganed.quickbuds.music.VoiceMusic

/**
 * Bike mode: on/off (as the quick settings tile), and what it turns on: auto-answer (and after how long), voice
 * answer, and voice music. A choice that needs permissions asks for them first. Settings › Calls.
 */
class BikeModeActivity : Activity() {

    private lateinit var bikeSwitch: Switch
    private lateinit var bikeSub: TextView
    private lateinit var autoSwitch: Switch
    private lateinit var delaySub: TextView
    private lateinit var voiceSwitch: Switch
    private lateinit var musicSwitch: Switch
    private lateinit var musicSub: TextView
    /** Permission requests refused for good: Android shows no dialog any more, so the app's settings open. */
    private val asked = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.bike_tile_label))

        bikeSwitch = SettingRowFactory.buildSwitch(this, BikeMode.isOn(this))
        val bikeRow = SettingRowFactory.build(this, R.drawable.ic_bike, R.string.bike_tile_label, 0, bikeSwitch) { bikeSwitch.performClick() }
        bikeSub = SettingRowFactory.subtitle(this, bikeRow)
        bikeSwitch.setOnCheckedChangeListener(bikeListener)
        root.addView(SettingRowFactory.card(this).apply { SettingRowFactory.addRow(this, bikeRow) })

        root.addView(SettingRowFactory.sectionLabel(this, R.string.bike_when_on))
        autoSwitch = SettingRowFactory.buildSwitch(this, BikeMode.autoAnswer(this))
        val autoRow = SettingRowFactory.build(this, R.drawable.ic_earbud, R.string.auto_answer_title, 0, autoSwitch) { autoSwitch.performClick() }
        autoSwitch.setOnCheckedChangeListener(autoListener)
        val delayRow = SettingRowFactory.build(this, 0, R.string.auto_answer_delay_title, 0, SettingRowFactory.buildChevron(this)) {
            BottomSheetDialog(this).title(getString(R.string.auto_answer_delay_title)).items(AutoAnswer.DELAYS.map { s ->
                BottomSheetDialog.Item(getString(R.string.auto_answer_seconds, s), s == BikeMode.delaySeconds(this)) {
                    choose { it.putInt(BikeMode.KEY_DELAY, s) }
                    paint()
                }
            }).show()
        }
        delaySub = SettingRowFactory.subtitle(this, delayRow)
        voiceSwitch = SettingRowFactory.buildSwitch(this, BikeMode.voiceAnswer(this))
        val voiceRow = SettingRowFactory.build(this, R.drawable.ic_earbud, R.string.va_title, 0, voiceSwitch) { voiceSwitch.performClick() }
        voiceSwitch.setOnCheckedChangeListener(voiceListener)
        // Voice music: the switch chooses it; the row opens its own screen (music app, speech model, access).
        musicSwitch = SettingRowFactory.buildSwitch(this, VoiceMusic.switchedOn(this))
        val musicRow = SettingRowFactory.build(this, R.drawable.ic_bike, R.string.vm_title, 0, musicSwitch) {
            startActivity(Intent(this, VoiceMusicActivity::class.java))
        }
        musicSub = SettingRowFactory.subtitle(this, musicRow)
        musicSwitch.setOnCheckedChangeListener(musicListener)
        root.addView(SettingRowFactory.card(this).apply {
            SettingRowFactory.addRow(this, autoRow)
            SettingRowFactory.addRow(this, delayRow)
            SettingRowFactory.addRow(this, voiceRow)
            SettingRowFactory.addRow(this, musicRow)
        })
        root.addView(TextView(this).apply {
            setText(R.string.bike_note); textSize = 13f
            setTextColor(ThemeRes.color(this@BikeModeActivity, R.attr.appColorTextSecondary))
            val p = ThemeRes.dp(this@BikeModeActivity, 4f)
            setPadding(p, ThemeRes.dp(this@BikeModeActivity, 10f), p, 0)
        })

        // Turn on by itself: riding fast, or a chosen Bluetooth device connects. Off again by the same thing.
        root.addView(SettingRowFactory.sectionLabel(this, R.string.bike_auto_title))
        speedSwitch = SettingRowFactory.buildSwitch(this, BikeAuto.speedOn(this))
        val speedRow = SettingRowFactory.build(this, R.drawable.ic_bike, R.string.bike_auto_speed, 0, speedSwitch) { speedSwitch.performClick() }
        speedSub = SettingRowFactory.subtitle(this, speedRow)
        speedSwitch.setOnCheckedChangeListener(speedListener)
        val deviceRow = SettingRowFactory.build(this, 0, R.string.bike_auto_bt, 0, SettingRowFactory.buildChevron(this)) { pickDevices() }
        deviceSub = SettingRowFactory.subtitle(this, deviceRow)
        root.addView(SettingRowFactory.card(this).apply {
            SettingRowFactory.addRow(this, speedRow)
            SettingRowFactory.addRow(this, deviceRow)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.color(this@BikeModeActivity, R.attr.appColorBg))
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        // A permission granted again (here or in Android's settings) changes no setting: the service looks again.
        BudsService.syncCalls(this)
        live.resume()
        paint()
    }

    override fun onPause() {
        live.pause()
        super.onPause()
    }

    /** Kept up to date while open: the quick settings tile turns Bike mode on and off from outside. */
    private val live = LiveScreen(this) { paint() }

    /**
     * Changes a choice. While Bike mode is on, a choice whose permissions are missing would leave it off: it asks
     * for them, and Bike mode comes back on when they are granted. Was: it went off with no message.
     */
    private fun choose(change: (android.content.SharedPreferences.Editor) -> Unit) {
        if (!BikeMode.choose(this, change)) ask(BikeMode.missingPermissions(this), REQUEST_BIKE)
    }

    private fun missing(perms: Array<String>) = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** Asks for [perms]; refused for good before, opens the app's settings instead. True: all granted already. */
    private fun ask(perms: List<String>, request: Int): Boolean {
        if (perms.isEmpty()) return true
        if (request in asked && perms.none { shouldShowRequestPermissionRationale(it) }) {
            ConfirmDialog.show(this, getString(R.string.va_perm_title), getString(R.string.va_perm_body),
                getString(R.string.perm_action), cancelRes = R.string.dialog_close) {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
            return false
        }
        asked += request
        requestPermissions(perms.toTypedArray(), request)
        return false
    }

    /** This screen is open: the background service may take the microphone now (voice answer, voice music). */
    private fun takeMicrophone() = startService(Intent(this, BudsService::class.java)
        .setAction(BudsService.ACTION_VOICE_PROBE).putExtra(BudsService.EXTRA_FIND_ON, true))

    private val bikeListener = CompoundButton.OnCheckedChangeListener { _, on ->
        if (on && !ask(BikeMode.missingPermissions(this), REQUEST_BIKE)) return@OnCheckedChangeListener paint()
        BikeMode.set(this, on)
        if (on) takeMicrophone()
        paint()
    }

    private val autoListener = CompoundButton.OnCheckedChangeListener { _, on ->
        if (on && !ask(missing(AutoAnswer.PERMISSIONS), REQUEST_AUTO)) return@OnCheckedChangeListener paint()
        choose { it.putBoolean(BikeMode.KEY_AUTO_ANSWER, on) }
        paint()
    }

    private val voiceListener = CompoundButton.OnCheckedChangeListener { _, on ->
        if (on && !ask(missing(VoiceAnswer.PERMISSIONS), REQUEST_VOICE)) return@OnCheckedChangeListener paint()
        choose { it.putBoolean(BikeMode.KEY_VOICE_ANSWER, on) }
        if (on) takeMicrophone()
        paint()
    }

    private lateinit var speedSwitch: Switch
    private lateinit var speedSub: TextView
    private lateinit var deviceSub: TextView

    /** Speed: the location, then "all the time" (the phone sits in a pocket with the screen off). */
    private val speedListener = CompoundButton.OnCheckedChangeListener { _, on ->
        BikeAuto.setSpeed(this, on)
        if (on && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
        } else if (on) askBackground()
        paint()
    }

    private fun askBackground() {
        if (BikeAuto.locationPermitted(this)) return
        ConfirmDialog.show(this, getString(R.string.dloc_bg_title), getString(R.string.bike_auto_bg_body), getString(R.string.dloc_continue)) {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_BACKGROUND)
        }
    }

    /** The paired Bluetooth devices; a tap chooses or unchooses one. */
    @SuppressLint("MissingPermission")
    private fun pickDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH)
        }
        val paired = runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList() }.getOrNull().orEmpty()
            .sortedBy { (it.name ?: it.address).lowercase() }
        if (paired.isEmpty()) return ConfirmDialog.show(this, getString(R.string.bike_auto_bt), getString(R.string.bike_auto_bt_empty),
            getString(R.string.dialog_close), cancelRes = null)
        val chosen = BikeAuto.devices(this).toMutableSet()
        val sheet = BottomSheetDialog(this).title(getString(R.string.bike_auto_bt)).message(getString(R.string.bike_auto_bt_sub))
            .dismissOnSelect(false).onDismiss { paint() }
        fun items(): List<BottomSheetDialog.Item> = paired.map { d ->
            BottomSheetDialog.Item(d.name ?: d.address, d.address in chosen) {
                if (!chosen.remove(d.address)) chosen += d.address
                BikeAuto.setDevices(this, chosen)
                sheet.items(items())
            }
        }
        sheet.items(items()).show()
    }

    /** The chosen devices by name, or "None". */
    @SuppressLint("MissingPermission")
    private fun deviceNames(): String {
        val chosen = BikeAuto.devices(this)
        if (chosen.isEmpty()) return getString(R.string.bike_auto_bt_none)
        val names = runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
            ?.filter { it.address in chosen }?.map { it.name ?: it.address } }.getOrNull()
        return names?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: chosen.joinToString(", ")
    }

    private val musicListener = CompoundButton.OnCheckedChangeListener { _, on ->
        if (on && !ask(missing(VoiceMusicActivity.PERMISSIONS), REQUEST_MUSIC)) return@OnCheckedChangeListener paint()
        VoiceMusic.setEnabled(this, on)
        if (on) takeMicrophone()
        // Not set up yet (no music app, model or access): its screen shows what is missing.
        if (on && VoiceMusic.app(this) == null) startActivity(Intent(this, VoiceMusicActivity::class.java))
        paint()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty() || grantResults.any { it != PackageManager.PERMISSION_GRANTED }) return paint()
        // Granted: do what the switch asked for.
        when (requestCode) {
            REQUEST_BIKE -> { BikeMode.set(this, true); takeMicrophone() }
            REQUEST_AUTO -> choose { it.putBoolean(BikeMode.KEY_AUTO_ANSWER, true) }
            REQUEST_VOICE -> { choose { it.putBoolean(BikeMode.KEY_VOICE_ANSWER, true) }; takeMicrophone() }
            REQUEST_MUSIC -> { VoiceMusic.setEnabled(this, true); takeMicrophone() }
            REQUEST_LOCATION -> askBackground()
            REQUEST_BLUETOOTH -> pickDevices()
        }
        paint()
    }

    /** The switches as saved (a refused permission leaves them off), and the subtitles. */
    private fun paint() {
        quiet(bikeSwitch, BikeMode.isOn(this), bikeListener)
        quiet(autoSwitch, BikeMode.autoAnswer(this), autoListener)
        quiet(voiceSwitch, BikeMode.voiceAnswer(this), voiceListener)
        quiet(musicSwitch, VoiceMusic.switchedOn(this), musicListener)
        bikeSub.text = summary(this)
        delaySub.text = getString(R.string.auto_answer_seconds, BikeMode.delaySeconds(this))
        musicSub.text = VoiceMusicActivity.summary(this)
        quiet(speedSwitch, BikeAuto.speedOn(this), speedListener)
        speedSub.text = getString(if (BikeAuto.speedOn(this) && !BikeAuto.locationPermitted(this)) R.string.dloc_needs_background
            else R.string.bike_auto_speed_sub)
        deviceSub.text = deviceNames()
    }

    private fun quiet(sw: Switch, on: Boolean, l: CompoundButton.OnCheckedChangeListener) {
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = on
        sw.setOnCheckedChangeListener(l)
    }

    companion object {
        private const val REQUEST_BIKE = 91
        private const val REQUEST_AUTO = 92
        private const val REQUEST_VOICE = 93
        private const val REQUEST_MUSIC = 94
        private const val REQUEST_LOCATION = 95
        private const val REQUEST_BACKGROUND = 96
        private const val REQUEST_BLUETOOTH = 97

        /** One line: on or off, then what it turns on. */
        fun summary(c: Context): String {
            val chosen = listOfNotNull(
                if (BikeMode.autoAnswer(c)) c.getString(R.string.auto_answer_title) else null,
                if (BikeMode.voiceAnswer(c)) c.getString(R.string.va_title) else null,
                if (VoiceMusic.switchedOn(c)) c.getString(R.string.vm_title) else null
            )
            return (listOf(c.getString(if (BikeMode.isOn(c)) R.string.bike_tile_on else R.string.bike_tile_off)) + chosen).joinToString(" · ")
        }
    }
}
