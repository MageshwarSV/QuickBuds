package com.spizganed.quickbuds.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.CompoundButton
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsDevice
import com.spizganed.quickbuds.findphone.FindPhone
import com.spizganed.quickbuds.findphone.FinderAlarm
import com.spizganed.quickbuds.widget.WidgetStateStore

/**
 * Find My Phone: on/off, the earbuds and whether they report taps, the alarm sound (QuickBuds' finder sound or a
 * picked file, or the phone's ringtone), Do Not Disturb access for Silent mode, and Test / Stop. Every status is
 * read from the system each time the screen paints ([LiveScreen]). Settings.
 */
class FindPhoneActivity : Activity() {

    private lateinit var sw: Switch
    private lateinit var status: TextView
    private lateinit var budsSub: TextView
    private lateinit var gestureSub: TextView
    private lateinit var finderRadio: RadioButton
    private lateinit var ringtoneRadio: RadioButton
    private lateinit var finderSub: TextView
    private lateinit var ringtoneSub: TextView
    private lateinit var resetRow: View
    private lateinit var dndSub: TextView
    private lateinit var testRow: View
    private lateinit var stopRow: View

    private fun dp(v: Float) = ThemeRes.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        fun note(res: Int) = TextView(this).apply {
            setText(res); textSize = 13f
            setTextColor(ThemeRes.color(this@FindPhoneActivity, R.attr.appColorTextSecondary))
            setPadding(dp(4f), dp(8f), dp(4f), 0)
        }
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.fp_title))

        sw = SettingRowFactory.buildSwitch(this, FindPhone.enabled(this))
        val row = SettingRowFactory.build(this, R.drawable.ic_earbud, R.string.fp_title, 0, sw) { sw.performClick() }
        status = SettingRowFactory.subtitle(this, row)
        sw.setOnCheckedChangeListener(listener)
        val budsRow = SettingRowFactory.build(this, 0, R.string.fp_earbuds, 0, null)
        budsSub = SettingRowFactory.subtitle(this, budsRow)
        val gestureRow = SettingRowFactory.build(this, 0, R.string.fp_gesture, 0, null)
        gestureSub = SettingRowFactory.subtitle(this, gestureRow)
        root.addView(SettingRowFactory.card(this).apply {
            SettingRowFactory.addRow(this, row)
            SettingRowFactory.addRow(this, budsRow)
            SettingRowFactory.addRow(this, gestureRow)
        })

        // The sound: one of two.
        root.addView(SettingRowFactory.sectionLabel(this, R.string.fp_sound_title))
        finderRadio = RadioButton(this).apply { isClickable = false }
        val finderRow = SettingRowFactory.build(this, 0, R.string.fp_sound_finder, 0, finderRadio) { choose(FindPhone.Sound.FINDER) }
        finderSub = SettingRowFactory.subtitle(this, finderRow)
        ringtoneRadio = RadioButton(this).apply { isClickable = false }
        val ringtoneRow = SettingRowFactory.build(this, 0, R.string.fp_sound_ringtone, 0, ringtoneRadio) { choose(FindPhone.Sound.RINGTONE) }
        ringtoneSub = SettingRowFactory.subtitle(this, ringtoneRow)
        val pickRow = SettingRowFactory.build(this, 0, R.string.fp_choose, 0, SettingRowFactory.buildChevron(this)) { pick() }
        resetRow = SettingRowFactory.build(this, 0, R.string.fp_reset, 0, null) {
            FindPhone.setCustomUri(this, null); paint()
        }
        val previewRow = SettingRowFactory.build(this, 0, R.string.fp_preview, 0, null) {
            FinderAlarm.ring(this, FinderAlarm.uriFor(this, FindPhone.sound(this)), "preview", loud = false)
        }
        root.addView(SettingRowFactory.card(this).apply {
            SettingRowFactory.addRow(this, finderRow)
            SettingRowFactory.addRow(this, ringtoneRow)
            SettingRowFactory.addRow(this, pickRow)
            SettingRowFactory.addRow(this, resetRow)
            SettingRowFactory.addRow(this, previewRow)
        })

        // Silent mode: the alarm channel, and Do Not Disturb access.
        root.addView(SettingRowFactory.sectionLabel(this, R.string.fp_silent_title))
        val dndRow = SettingRowFactory.build(this, 0, R.string.fp_dnd, 0, SettingRowFactory.buildChevron(this)) {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        }
        dndSub = SettingRowFactory.subtitle(this, dndRow)
        root.addView(SettingRowFactory.card(this).apply { SettingRowFactory.addRow(this, dndRow) })
        root.addView(note(R.string.fp_silent_note))

        // Test and stop.
        root.addView(SettingRowFactory.sectionLabel(this, R.string.fp_test_title))
        testRow = SettingRowFactory.build(this, 0, R.string.fp_test, 0, null) {
            FinderAlarm.ring(this, FinderAlarm.uriFor(this, FindPhone.sound(this)), "test")
        }
        stopRow = SettingRowFactory.build(this, 0, R.string.fp_stop, 0, null) { FinderAlarm.stop(this, "stop button") }
        root.addView(SettingRowFactory.card(this).apply {
            SettingRowFactory.addRow(this, testRow)
            SettingRowFactory.addRow(this, stopRow)
        })
        root.addView(note(R.string.fp_note))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.color(this@FindPhoneActivity, R.attr.appColorBg))
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        live.resume()
        paint()
    }

    override fun onPause() {
        live.pause()
        super.onPause()
    }

    /** Kept up to date: the earbuds connect, the alarm starts or stops, access is granted in Android. */
    private val live = LiveScreen(this) { paint() }

    private val listener = CompoundButton.OnCheckedChangeListener { _, on ->
        FindPhone.setEnabled(this, on)
        // Android 13+: the Stop button lives in a notification.
        if (on && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFY)
        }
        paint()
    }

    private fun choose(s: FindPhone.Sound) {
        FindPhone.setSound(this, s)
        paint()
    }

    /** An audio file from the phone, kept with a lasting read permission. */
    private fun pick() {
        runCatching {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), REQUEST_PICK)
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Activity result API is not used in this app (no AndroidX Activity).")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        FindPhone.setCustomUri(this, uri)
        FindPhone.setSound(this, FindPhone.Sound.FINDER)
        paint()
    }

    private fun paint() {
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = FindPhone.enabled(this)
        sw.setOnCheckedChangeListener(listener)
        status.text = summary(this)
        val connected = WidgetStateStore.read(this).connected
        val name = BudsDevice.find(this)?.let { runCatching { com.spizganed.quickbuds.bluetooth.AudioDevice.name(it) }.getOrNull() }
        budsSub.text = if (connected) name ?: getString(R.string.fp_connected) else getString(R.string.fp_not_connected)
        gestureSub.setText(when {
            !connected -> R.string.fp_gesture_wait
            FindPhone.gestures == true -> R.string.fp_gesture_ok
            FindPhone.gestures == false -> R.string.fp_gesture_no
            else -> R.string.fp_gesture_wait
        })
        val sound = FindPhone.sound(this)
        finderRadio.isChecked = sound == FindPhone.Sound.FINDER
        ringtoneRadio.isChecked = sound == FindPhone.Sound.RINGTONE
        val custom = FindPhone.customUri(this)
        finderSub.text = custom?.let { fileName(it) } ?: getString(R.string.fp_finder_default)
        resetRow.visibility = if (custom != null) View.VISIBLE else View.GONE
        ringtoneSub.text = FinderAlarm.ringtoneUri(this)
            ?.let { runCatching { RingtoneManager.getRingtone(this, it)?.getTitle(this) }.getOrNull() }
            ?: getString(R.string.fp_no_ringtone)
        val nm = getSystemService(NotificationManager::class.java)
        dndSub.setText(if (nm?.isNotificationPolicyAccessGranted == true) R.string.fp_dnd_on else R.string.fp_dnd_off)
        testRow.visibility = if (FinderAlarm.ringing) View.GONE else View.VISIBLE
        stopRow.visibility = if (FinderAlarm.ringing) View.VISIBLE else View.GONE
    }

    private fun fileName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    companion object {
        private const val REQUEST_PICK = 121
        private const val REQUEST_NOTIFY = 122

        /** One line: off, on, or ringing. */
        fun summary(c: Context): String = c.getString(when {
            FinderAlarm.ringing -> R.string.fp_ringing
            FindPhone.enabled(c) -> R.string.fp_sub_on
            else -> R.string.fp_sub_off
        })
    }
}
