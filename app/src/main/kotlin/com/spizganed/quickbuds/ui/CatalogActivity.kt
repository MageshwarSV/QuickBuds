package com.spizganed.quickbuds.ui

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.DeviceCatalog

/**
 * Supported devices: every brand [DeviceCatalog] knows, A to Z, with its series, the kind of device each is, example
 * models and what QuickBuds does with them. A note on top says the rest: a device not listed works too, recognised
 * when it connects. The brands of `models.json` get full controls.
 */
class CatalogActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val p = ThemeRes.palette(this)
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.catalog_title))
        root.addView(TextView(this).apply {
            setText(R.string.catalog_note)
            setTextColor(p.textSecondary)
            textSize = 13f
            setPadding(ThemeRes.dp(this@CatalogActivity, 4f), 0, 0, ThemeRes.dp(this@CatalogActivity, 4f))
        })

        for (brand in DeviceCatalog.BRANDS.sortedBy { it.name.lowercase() }) {
            val full = brand.name in FULL_CONTROLS
            root.addView(SettingRowFactory.sectionLabel(this, R.string.catalog_title).apply { text = brand.name })
            val card = SettingRowFactory.card(this)
            for (series in brand.series) {
                val row = SettingRowFactory.build(this, DeviceKinds.icon(series.kind), 0, 0, null)
                row.findViewWithTag<TextView>(SettingRowFactory.TITLE_TAG).text = series.name
                SettingRowFactory.subtitle(this, row).text = listOfNotNull(
                    DeviceKinds.label(series.kind)?.let { getString(it) },
                    getString(DeviceKinds.features(series.kind, full)),
                    series.models.take(MODELS_SHOWN).joinToString(", ").ifEmpty { null }
                ).joinToString(" · ")
                SettingRowFactory.addRow(card, row)
            }
            root.addView(card)
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.background)
            addView(root)
        })
    }

    private companion object {
        /** Brands whose protocol QuickBuds speaks (models.json): ANC, EQ, gestures, left / right / case. */
        val FULL_CONTROLS = setOf("OnePlus", "OPPO", "realme", "DIZO")
        /** Example models per series row; a long list would hide the series. */
        const val MODELS_SHOWN = 8
    }
}
