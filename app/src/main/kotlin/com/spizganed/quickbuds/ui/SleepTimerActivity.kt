package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.sleep.SleepPlan
import com.spizganed.quickbuds.sleep.SleepTimer
import java.time.ZonedDateTime
import java.util.Date

/**
 * Sleep timer: stop all audio after a set time ("Timer": hours and minutes) or at a clock time ("At a time", in the
 * phone's 12 or 24 hour format). For every connected device. While it runs: when, how long is left, Cancel.
 * Android lets no app switch Bluetooth devices off: the note says what happens instead.
 */
class SleepTimerActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var tab = 0
    private lateinit var tabs: AncSegmentedView
    private lateinit var setup: LinearLayout
    private lateinit var timerBox: LinearLayout
    private lateinit var clockBox: LinearLayout
    private lateinit var hours: NumberPicker
    private lateinit var minutes: NumberPicker
    private lateinit var clock: TimePicker
    private lateinit var activeBox: LinearLayout
    private lateinit var activeAt: TextView
    private lateinit var activeLeft: TextView
    private lateinit var exactRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val p = ThemeRes.palette(this)
        fun dp(v: Float) = ThemeRes.dp(this, v)
        fun text(s: String, size: Float, colorAttr: Int) = TextView(this).apply {
            text = s; textSize = size; gravity = Gravity.CENTER
            setTextColor(ThemeRes.color(this@SleepTimerActivity, colorAttr))
        }
        fun lp(top: Float = 0f) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }
        fun button(label: Int, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            setText(label); textSize = 16f; typeface = ThemeRes.medium(context); gravity = Gravity.CENTER
            setTextColor(if (primary) ThemeRes.onFill(this@SleepTimerActivity) else p.accent)
            background = ThemeRes.ripple(this@SleepTimerActivity,
                if (primary) ThemeRes.pill(this@SleepTimerActivity, p.accent, 26f) else ThemeRes.card(this@SleepTimerActivity, 26f))
            setOnClickListener { Haptics.commit(this); onClick() }
        }
        fun picker(max: Int) = NumberPicker(this).apply { minValue = 0; maxValue = max; wrapSelectorWheel = true }
        fun labelled(label: Int, v: View) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(v)
            addView(text(getString(label), 13f, R.attr.appColorTextSecondary))
        }

        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.sleep_title))

        // ---- Running
        activeAt = text("", 30f, R.attr.appColorTextPrimary).apply { typeface = ThemeRes.headline(this@SleepTimerActivity) }
        activeLeft = text("", 16f, R.attr.appColorTextSecondary)
        activeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeRes.card(this@SleepTimerActivity, 24f)
            setPadding(dp(16f), dp(20f), dp(16f), dp(16f))
            addView(activeAt)
            addView(activeLeft, lp(4f))
            addView(button(R.string.sleep_cancel, false) { SleepTimer.cancel(this@SleepTimerActivity); render() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52f)).apply { topMargin = dp(16f) })
        }
        root.addView(activeBox, lp(12f))

        // ---- Setting it
        tabs = AncSegmentedView(this, listOf(getString(R.string.sleep_tab_timer), getString(R.string.sleep_tab_clock))).apply {
            selected = 0
            onSegmentTapped = { i -> tab = i; renderTab() }
        }
        hours = picker(23)
        minutes = picker(59)
        timerBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(labelled(R.string.sleep_hours, hours))
            addView(labelled(R.string.sleep_minutes, minutes), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(32f) })
        }
        minutes.value = 30
        clock = TimePicker(this, null, android.R.attr.timePickerStyle).apply {
            setIs24HourView(DateFormat.is24HourFormat(this@SleepTimerActivity))
        }
        clockBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; addView(clock) }
        setup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tabs, lp(8f))
            addView(timerBox, lp(12f))
            addView(clockBox, lp(12f))
            addView(button(R.string.sleep_start, true) { start() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52f)).apply { topMargin = dp(16f) })
        }
        root.addView(setup, lp(12f))

        // ---- Exact timing (Android 12+ asks the user)
        exactRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(text(getString(R.string.sleep_exact), 13f, R.attr.appColorTextPrimary).apply { gravity = Gravity.START },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button(R.string.sleep_exact_btn, false) { askExact() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40f)).apply { marginStart = dp(8f) }
                    .also { lpw -> lpw.width = LinearLayout.LayoutParams.WRAP_CONTENT })
        }
        root.addView(exactRow, lp(16f))
        root.addView(text(getString(R.string.sleep_note), 13f, R.attr.appColorTextSecondary).apply { gravity = Gravity.START }, lp(16f))

        setContentView(ScrollView(this).apply { setBackgroundColor(p.background); isFillViewport = true; addView(root) })
        renderTab()
    }

    override fun onResume() {
        super.onResume()
        SleepTimer.rearm(this)
        render()
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun renderTab() {
        tabs.selected = tab
        timerBox.visibility = if (tab == 0) View.VISIBLE else View.GONE
        clockBox.visibility = if (tab == 1) View.VISIBLE else View.GONE
    }

    /** Running: when and how long; else the setup. Ticks every 30 s while running. */
    private fun render() {
        handler.removeCallbacksAndMessages(null)
        val at = SleepTimer.at(this)
        activeBox.visibility = if (at != null) View.VISIBLE else View.GONE
        setup.visibility = if (at == null) View.VISIBLE else View.GONE
        exactRow.visibility = if (SleepTimer.exactAllowed(this)) View.GONE else View.VISIBLE
        if (at == null) return
        activeAt.text = getString(R.string.sleep_active, DateFormat.getTimeFormat(this).format(Date(at)))
        val left = (at - System.currentTimeMillis()).coerceAtLeast(0)
        // Round up to the minute: "In 1m" until it fires, never "In 0m".
        activeLeft.text = getString(R.string.sleep_remaining, UsageFormat.duration(this, ((left + 59_999) / 60_000) * 60_000))
        handler.postDelayed({ render() }, 30_000)
    }

    private fun start() {
        // A number typed on the keyboard counts only once the picker lets go of the focus.
        hours.clearFocus(); minutes.clearFocus(); clock.clearFocus()
        val at = if (tab == 0) SleepPlan.afterDuration(System.currentTimeMillis(), hours.value, minutes.value)
        else SleepPlan.atClock(ZonedDateTime.now(), clock.hour, clock.minute).toInstant().toEpochMilli()
        if (at == null) {
            Toast.makeText(this, R.string.sleep_zero, Toast.LENGTH_SHORT).show()
            return
        }
        SleepTimer.schedule(this, at)
        render()
    }

    private fun askExact() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", packageName, null)))
        }
    }
}
