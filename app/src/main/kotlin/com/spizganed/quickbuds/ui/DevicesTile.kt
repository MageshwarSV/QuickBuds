package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Intent
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.AudioOutput
import com.spizganed.quickbuds.bluetooth.ConnectedDevices
import com.spizganed.quickbuds.bluetooth.DeviceCatalog
import com.spizganed.quickbuds.notify.NotificationArt
import com.spizganed.quickbuds.proximity.ProximityMonitor
import com.spizganed.quickbuds.usage.Usage
import com.spizganed.quickbuds.usage.UsageStats
import java.util.Locale

/**
 * The home screen's device switcher, for when devices of other brands are connected ([ConnectedDevices]).
 *
 *  - [strip]: every connected device as a small card, the selected one first and outlined, "Playing" on the one
 *    the phone plays audio on. Shown with two devices or more; it scrolls sideways.
 *  - [detail]: the selected device's battery and options (audio output, usage, loss protection). For the supported
 *    buds, [showBudsControls] shows their own tiles instead: battery, noise control, settings rows.
 *
 * The selected device: the one the user tapped while it stays connected; else the one playing audio; else the buds.
 * Nothing connected but the buds, or nothing at all: the home screen as before.
 */
class DevicesTile(
    private val activity: Activity,
    private val strip: LinearLayout,
    private val detail: LinearLayout,
    private val showBudsControls: (Boolean) -> Unit
) {

    private val listener: () -> Unit = { render() }
    private val audio = AudioOutput.Watcher(activity) { render() }
    private var buds: ConnectedDevices.Entry? = null
    private var scroll: HorizontalScrollView? = null
    private var firstShown: String? = null

    fun start() {
        ConnectedDevices.addListener(listener)
        audio.start()
        render()
    }

    fun stop() {
        ConnectedDevices.removeListener(listener)
        audio.stop()
    }

    /** The supported buds while their link is up; null while it is down. */
    fun setBuds(entry: ConnectedDevices.Entry?) {
        if (entry == buds) return
        buds = entry
        render()
    }

    private fun dp(v: Float) = ThemeRes.dp(activity, v)

    fun render() {
        if (activity.isFinishing || activity.isDestroyed) return
        val others = ConnectedDevices.all()
        if (picked != null && buds?.address != picked && others.none { it.address == picked }) picked = null
        val playing = AudioOutput.current(activity)
        val plan = DeviceOrder.plan(buds, others, picked, playing)
        if (plan == null) {
            strip.visibility = View.GONE
            detail.visibility = View.GONE
            showBudsControls(true)
            return
        }
        val selected = plan.devices.first()
        renderStrip(plan.devices, playing)
        if (plan.budsControls) {
            detail.visibility = View.GONE
            showBudsControls(true)
        } else {
            showBudsControls(false)
            renderDetail(selected, playing == selected.address)
        }
    }

    // ==================== Strip ====================

    private fun renderStrip(devices: List<ConnectedDevices.Entry>, playing: String?) {
        // Back to the start when another device comes first: the selected card is always in view.
        val keepX = if (devices.first().address == firstShown) scroll?.scrollX ?: 0 else 0
        firstShown = devices.first().address
        strip.removeAllViews()
        scroll = null
        strip.visibility = if (devices.size < 2) View.GONE else View.VISIBLE
        if (devices.size < 2) return   // DeviceOrder.Plan.strip
        strip.addView(SettingRowFactory.sectionLabel(activity, R.string.dual_section_devices).apply {
            setPadding(paddingLeft, 0, paddingRight, paddingBottom)
        })
        // About two and a half cards across: the cut one says there is more to the side.
        val full = strip.width.takeIf { it > 0 } ?: (activity.resources.displayMetrics.widthPixels - dp(32f))
        val width = if (devices.size == 2) (full - dp(10f)) / 2 else (full / 2.4f).toInt()
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        devices.forEachIndexed { i, d ->
            row.addView(chip(d, selected = i == 0, playing = d.address == playing),
                LinearLayout.LayoutParams(width, MATCH_PARENT).apply { if (i > 0) marginStart = dp(10f) })
        }
        scroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
            post { scrollTo(keepX, 0) }
        }
        strip.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** One device in the strip: a small ring, the name, and "Playing" or the battery. A tap selects it. */
    private fun chip(d: ConnectedDevices.Entry, selected: Boolean, playing: Boolean): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = ThemeRes.ripple(activity, ThemeRes.group(activity))
        if (selected) foreground = ThemeRes.selectedBorder(activity, ThemeRes.color(activity, R.attr.appColorAccent))
        clipToOutline = true
        setPadding(dp(10f), dp(12f), dp(10f), dp(10f))
        val (part, level, charging) = summaryRing(d)
        addView(ImageView(activity).apply {
            setImageBitmap(NotificationArt.batteryRing(activity, part, level, charging, CHIP_RING_DP))
        }, LinearLayout.LayoutParams(dp(CHIP_RING_DP), dp(CHIP_RING_DP)))
        addView(line(d.name, 13f, R.attr.appColorTextPrimary, medium = true),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6f) })
        addView(line(if (playing) activity.getString(R.string.devices_playing) else percent(level), 12f,
            if (playing) R.attr.appColorAccent else R.attr.appColorTextSecondary, medium = playing),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setOnClickListener {
            if (selected) return@setOnClickListener
            Haptics.tick(activity)
            picked = d.address
            render()
        }
    }

    /** One ring for a whole device: earbuds that report each bud show the lower one. */
    private fun summaryRing(d: ConnectedDevices.Entry): Triple<NotificationArt.Part, Int, Boolean> {
        val p = d.parts ?: return Triple(DeviceKinds.ringPart(d.kind), d.level, false)
        val buds = listOf(p.left to p.leftCharging, p.right to p.rightCharging).filter { it.first > 0 }
        val low = buds.minByOrNull { it.first } ?: return Triple(NotificationArt.Part.BOTH, -1, false)
        return Triple(NotificationArt.Part.BOTH, low.first, low.second)
    }

    // ==================== Detail ====================

    private fun renderDetail(d: ConnectedDevices.Entry, playing: Boolean) {
        detail.removeAllViews()
        detail.visibility = View.VISIBLE
        detail.addView(batteryCard(d), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val options = SettingRowFactory.card(activity)
        (options.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12f)
        // Where the sound goes: Android's own switcher, the only way an app can move it.
        val audioRow = SettingRowFactory.build(activity, R.drawable.ic_kind_speaker, R.string.devices_audio_title,
            if (playing) R.string.devices_audio_here else R.string.devices_audio_switch,
            if (playing) null else SettingRowFactory.buildChevron(activity)
        ) {
            if (playing) return@build
            Toast.makeText(activity, activity.getString(R.string.devices_audio_pick, d.name), Toast.LENGTH_LONG).show()
            AudioOutput.openSwitcher(activity)
        }
        SettingRowFactory.addRow(options, audioRow)

        SettingRowFactory.addRow(options, SettingRowFactory.build(activity, R.drawable.ic_find_buds, R.string.find_nav_title,
            R.string.find_nav_sub, SettingRowFactory.buildChevron(activity)
        ) { activity.startActivity(FindNearbyActivity.intent(activity, d.address, d.name)) })

        val usageRow = SettingRowFactory.build(activity, R.drawable.ic_usage, R.string.usage_title, 0,
            SettingRowFactory.buildChevron(activity)
        ) { activity.startActivity(Intent(activity, UsageActivity::class.java).putExtra(UsageActivity.EXTRA_DEVICE, d.address)) }
        val usageSub = SettingRowFactory.subtitle(activity, usageRow)
        SettingRowFactory.addRow(options, usageRow)
        if (Usage.enabled(activity)) Usage.load(activity) { s ->
            val zone = java.time.ZoneId.systemDefault()
            val today = java.time.Instant.ofEpochMilli(s.nowMs).atZone(zone).toLocalDate()
            val ms = UsageStats.byDay(s.spans, s.summaries, zone, d.address)[today]?.totalMs ?: 0L
            usageSub.text = activity.getString(R.string.usage_tile_today, UsageFormat.duration(activity, ms))
        }

        // A speaker stays where it is put: no loss warning for it.
        if (d.kind != DeviceCatalog.Kind.SPEAKER) {
            val on = ProximityMonitor.enabled(activity)
            SettingRowFactory.addRow(options, SettingRowFactory.build(activity, R.drawable.ic_find_buds,
                R.string.settings_loss_title, if (on) R.string.loss_state_active else R.string.settings_loss_sub,
                SettingRowFactory.buildChevron(activity)
            ) { activity.startActivity(Intent(activity, SettingsActivity::class.java)) })
        }
        detail.addView(options)
    }

    /** The selected device's battery, as the buds' battery tile shows theirs: Left, Case, Right, or one ring. */
    private fun batteryCard(d: ConnectedDevices.Entry): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = ThemeRes.group(activity)
        setPadding(dp(16f), dp(16f), dp(16f), dp(14f))
        addView(rings(d))
        addView(line(d.name, 15f, R.attr.appColorTextPrimary, medium = true),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10f) })
        val sub = listOfNotNull(DeviceCatalog.brandOf(d.name)?.name, DeviceKinds.label(d.kind)?.let { activity.getString(it) })
        if (sub.isNotEmpty()) addView(line(sub.joinToString(" · "), 13f, R.attr.appColorTextSecondary),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2f) })
    }

    private fun rings(d: ConnectedDevices.Entry): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        val p = d.parts
        val shown = if (p != null) listOfNotNull(
            if (p.left >= 0) Triple(NotificationArt.Part.LEFT, p.left, p.leftCharging) to R.string.status_left else null,
            if (p.case >= 0) Triple(NotificationArt.Part.CASE, p.case, p.caseCharging) to R.string.status_case else null,
            if (p.right >= 0) Triple(NotificationArt.Part.RIGHT, p.right, p.rightCharging) to R.string.status_right else null
        ) else listOf(Triple(DeviceKinds.ringPart(d.kind), d.level, false) to (DeviceKinds.label(d.kind) ?: R.string.battery_label_buds))
        shown.forEachIndexed { i, (ring, label) ->
            addView(ring(ring.first, ring.second, ring.third, label),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i > 0) marginStart = dp(14f) })
        }
    }

    private fun ring(part: NotificationArt.Part, level: Int, charging: Boolean, label: Int): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            contentDescription = activity.getString(label) + " " + percent(level)
            addView(ImageView(activity).apply {
                setImageBitmap(NotificationArt.batteryRing(activity, part, level, charging, RING_DP))
            }, LinearLayout.LayoutParams(dp(RING_DP), dp(RING_DP)))
            addView(line(percent(level), 13f, R.attr.appColorTextSecondary, medium = true),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4f) })
        }

    private fun percent(level: Int) = if (level > 0) String.format(Locale.getDefault(), "%d%%", level) else "—"

    private fun line(text: String, sizeSp: Float, colorAttr: Int, medium: Boolean = false) = TextView(activity).apply {
        this.text = text
        textSize = sizeSp
        if (medium) typeface = ThemeRes.medium(activity)
        setTextColor(ThemeRes.color(activity, colorAttr))
        gravity = Gravity.CENTER
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private companion object {
        const val RING_DP = 72f
        const val CHIP_RING_DP = 44f

        /** The device the user tapped; kept while it stays connected, across screens. */
        var picked: String? = null
    }
}
