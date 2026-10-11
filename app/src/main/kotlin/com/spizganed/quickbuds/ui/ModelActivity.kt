package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.DeviceCatalog
import com.spizganed.quickbuds.bluetooth.ConnectedAudio
import com.spizganed.quickbuds.usage.Usage
import com.spizganed.quickbuds.usage.UsageDevice
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.widget.AncWidgetProvider

/**
 * The model list, opened from the header button before the connect pill. First "Your earbuds": every pair that ever
 * connected to this phone, any brand, with its brand and model; the connected ones first and marked. A tap opens
 * its usage. Then Automatic (what
 * [ModelCatalog] detects) and every model HeyMelody's list has, by brand (realme and its DIZO
 * included). A pick overrides detection until Automatic is picked again or
 * other buds connect. The rows are built once and only recoloured, so the selection outline slides
 * ([SelectionSlider]).
 */
class ModelActivity : Activity() {

    /** [id] is null for the Automatic row. */
    private class Item(val id: String?, val row: View, val title: TextView, val plain: ColorStateList)

    private val items = ArrayList<Item>()
    private lateinit var selection: SelectionSlider

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val models = ModelCatalog.all(this)
        // Two ids share a name (colour ranges, regional variants): those rows show their id.
        val nameCount = models.groupingBy { it.name }.eachCount()

        val root = SettingRowFactory.screen(this)
        selection = SelectionSlider(root)
        root.addView(SettingRowFactory.title(this, R.string.model_title))

        // Your earbuds: filled once the device list and the connected set are read.
        root.addView(SettingRowFactory.sectionLabel(this, R.string.model_yours))
        yours = SettingRowFactory.splitList(this)
        root.addView(yours)
        // Every brand and series QuickBuds knows, and what it does with each kind of device.
        root.addView(SettingRowFactory.splitList(this).also { list ->
            (list.layoutParams as LinearLayout.LayoutParams).topMargin = ThemeRes.dp(this, 8f)
            SettingRowFactory.addSplit(list, SettingRowFactory.build(this, R.drawable.ic_model_list, R.string.catalog_title,
                R.string.catalog_sub, SettingRowFactory.buildChevron(this)) {
                startActivity(android.content.Intent(this, CatalogActivity::class.java))
            })
        })

        fun row(id: String?, title: String, subtitle: String?, onPick: () -> Unit): View {
            val r = SettingRowFactory.build(this, 0, 0, 0, null) {
                if (id == ModelCatalog.manual(this)) return@build
                Haptics.commit(root)
                onPick()
                AncWidgetProvider.refreshAll(this)
                show()
            }
            val t = r.findViewWithTag<TextView>(SettingRowFactory.TITLE_TAG).apply { text = title }
            if (subtitle != null) SettingRowFactory.subtitle(this, r).text = subtitle
            items.add(Item(id, r, t, t.textColors))
            return r
        }

        val auto = SettingRowFactory.splitList(this)
        val detected = ModelCatalog.detected(this)?.name
        SettingRowFactory.addSplit(auto, row(null, getString(R.string.model_auto),
            if (detected != null) getString(R.string.model_detected, detected) else getString(R.string.model_not_detected)
        ) { ModelCatalog.setManual(this, null) })
        root.addView(auto)

        for ((brand, label) in listOf("OnePlus" to R.string.model_brand_oneplus, "OPPO" to R.string.model_brand_oppo,
                "realme" to R.string.model_brand_realme, "DIZO" to R.string.model_brand_dizo)) {
            root.addView(SettingRowFactory.sectionLabel(this, label))
            val card = SettingRowFactory.splitList(this)
            for (m in models.filter { it.name.startsWith("$brand ") }.sortedBy { it.name.lowercase() }) {
                SettingRowFactory.addSplit(card, row(m.id, m.name, m.id.takeIf { nameCount[m.name]!! > 1 }) {
                    ModelCatalog.setManual(this, m.id)
                })
            }
            root.addView(card)
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.palette(this@ModelActivity).background)
            addView(root)
        })
        show()
    }

    private lateinit var yours: LinearLayout

    override fun onResume() {
        super.onResume()
        Usage.load(this) { s -> ConnectedAudio.query(this) { connected -> fillYours(s, connected?.keys.orEmpty()) } }
    }

    /**
     * One row per pair of earbuds this phone has seen, connected ones first, then the most recently used. The row
     * names the brand ([Brands]) and what QuickBuds can do with it: everything for the models it speaks to, the
     * standard Bluetooth features for the rest.
     */
    private fun fillYours(s: Usage.Snapshot, connected: Set<String>) {
        if (isFinishing || isDestroyed) return
        yours.removeAllViews()
        val lastUsed = s.spans.groupBy { it.deviceId }.mapValues { (_, v) -> v.maxOf { it.endMs } }
        val devices = s.devices.sortedWith(compareByDescending<UsageDevice> { it.id in connected }
            .thenByDescending { lastUsed[it.id] ?: 0L })
        if (devices.isEmpty()) {
            yours.addView(TextView(this).apply {
                setText(R.string.model_yours_empty)
                setTextColor(ThemeRes.palette(this@ModelActivity).textSecondary)
                textSize = 13f
                setPadding(ThemeRes.dp(this@ModelActivity, 4f), 0, 0, 0)
            })
            return
        }
        val accent = ThemeRes.palette(this).accent
        for (d in devices) {
            val supported = d.model != null && ModelCatalog.all(this).any { it.name == d.model }
            val kind = DeviceKinds.of(d)
            val state = if (d.id in connected) getString(R.string.conn_on)
                else lastUsed[d.id]?.let { getString(R.string.model_last_used, UsageFormat.dayShort(this,
                    java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate())) }
            val r = SettingRowFactory.build(this, DeviceKinds.icon(kind), 0, 0, SettingRowFactory.buildChevron(this)) {
                startActivity(android.content.Intent(this, UsageActivity::class.java).putExtra(UsageActivity.EXTRA_DEVICE, d.id))
            }
            r.findViewWithTag<TextView>(SettingRowFactory.TITLE_TAG).apply {
                text = d.label
                if (d.id in connected) setTextColor(accent)
            }
            SettingRowFactory.subtitle(this, r).text = listOfNotNull(
                DeviceCatalog.brandOf(d.name, d.model)?.name, DeviceKinds.label(kind)?.let { getString(it) }, state,
                getString(DeviceKinds.features(kind, supported))
            ).joinToString(" · ")
            SettingRowFactory.addSplit(yours, r)
        }
    }

    /** Accent label and the sliding outline on the manual model's row (Automatic when none). */
    private fun show() {
        val accent = ThemeRes.palette(this).accent
        val manual = ModelCatalog.manual(this)
        for (i in items) i.title.setTextColor(if (i.id == manual) ColorStateList.valueOf(accent) else i.plain)
        selection.moveTo(items.firstOrNull { it.id == manual }?.row, accent)
    }
}
