package com.spizganed.quickbuds.ui

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.CompoundButton
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.notify.ChargeReminder
import java.util.Calendar

/** Charge reminder: on/off, the time of day, and the level below which it reminds. Settings › Usage and loss protection. */
class ChargeReminderActivity : Activity() {

    private lateinit var sw: Switch
    private lateinit var status: TextView
    private lateinit var timeSub: TextView
    private lateinit var belowSub: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.cr_title))

        sw = SettingRowFactory.buildSwitch(this, ChargeReminder.enabled(this))
        val row = SettingRowFactory.build(this, R.drawable.ic_case, R.string.cr_title, 0, sw) { sw.performClick() }
        status = SettingRowFactory.subtitle(this, row)
        sw.setOnCheckedChangeListener(listener)
        val timeRow = SettingRowFactory.build(this, 0, R.string.cr_time, 0, SettingRowFactory.buildChevron(this)) { pickTime() }
        timeSub = SettingRowFactory.subtitle(this, timeRow)
        val belowRow = SettingRowFactory.build(this, 0, R.string.cr_below, 0, SettingRowFactory.buildChevron(this)) {
            BottomSheetDialog(this).title(getString(R.string.cr_below)).items(ChargeReminder.LEVELS.map { l ->
                BottomSheetDialog.Item(getString(R.string.cr_percent, l), l == ChargeReminder.below(this)) {
                    ChargeReminder.set(this, below = l)
                    paint()
                }
            }).show()
        }
        belowSub = SettingRowFactory.subtitle(this, belowRow)
        root.addView(SettingRowFactory.card(this).apply {
            SettingRowFactory.addRow(this, row)
            SettingRowFactory.addRow(this, timeRow)
            SettingRowFactory.addRow(this, belowRow)
        })
        root.addView(TextView(this).apply {
            setText(R.string.cr_note); textSize = 13f
            setTextColor(ThemeRes.color(this@ChargeReminderActivity, R.attr.appColorTextSecondary))
            val p = ThemeRes.dp(this@ChargeReminderActivity, 4f)
            setPadding(p, ThemeRes.dp(this@ChargeReminderActivity, 10f), p, 0)
        })
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.color(this@ChargeReminderActivity, R.attr.appColorBg))
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        paint()
    }

    private val listener = CompoundButton.OnCheckedChangeListener { _, on ->
        ChargeReminder.set(this, enabled = on)
        // Android 13+: a reminder is a notification.
        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFY)
        }
        paint()
    }

    private fun pickTime() {
        val m = ChargeReminder.minuteOfDay(this)
        TimePickerDialog(this, { _, h, min -> ChargeReminder.set(this, minute = h * 60 + min); paint() },
            m / 60, m % 60, DateFormat.is24HourFormat(this)).show()
    }

    private fun paint() {
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = ChargeReminder.enabled(this)
        sw.setOnCheckedChangeListener(listener)
        status.text = summary(this)
        timeSub.text = time(this, ChargeReminder.minuteOfDay(this))
        belowSub.text = getString(R.string.cr_percent, ChargeReminder.below(this))
    }

    companion object {
        private const val REQUEST_NOTIFY = 101

        /** The time of day in the user's format: "21:00", "9:00 PM". */
        fun time(c: Context, minute: Int): String = DateFormat.getTimeFormat(c).format(Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minute / 60); set(Calendar.MINUTE, minute % 60)
        }.time)

        /** One line: off, or the time and the level. */
        fun summary(c: Context): String = if (!ChargeReminder.enabled(c)) c.getString(R.string.cr_off)
            else c.getString(R.string.cr_on, time(c, ChargeReminder.minuteOfDay(c)), ChargeReminder.below(c))
    }
}
