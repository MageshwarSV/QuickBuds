package com.spizganed.quickbuds.ui

import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.DeviceCatalog
import com.spizganed.quickbuds.bluetooth.DeviceCatalog.Kind
import com.spizganed.quickbuds.notify.NotificationArt
import com.spizganed.quickbuds.usage.UsageDevice

/** What each kind of device looks like and gets, in one place: icon, name, battery-ring glyph, features. */
object DeviceKinds {

    /** The stored kind, else what the catalog makes of the names. */
    fun of(d: UsageDevice): Kind? =
        d.kind?.let { k -> Kind.values().firstOrNull { it.name == k } } ?: DeviceCatalog.kindOf(d.name, d.model)

    fun icon(kind: Kind?): Int = when (kind) {
        Kind.NECKBAND -> R.drawable.ic_kind_neckband
        Kind.HEADPHONES -> R.drawable.ic_kind_headphones
        Kind.SPEAKER -> R.drawable.ic_kind_speaker
        else -> R.drawable.ic_earbud
    }

    fun label(kind: Kind?): Int? = when (kind) {
        Kind.TWS -> R.string.kind_tws
        Kind.NECKBAND -> R.string.kind_neckband
        Kind.HEADPHONES -> R.string.kind_headphones
        Kind.SPEAKER -> R.string.kind_speaker
        null -> null
    }

    /** The glyph inside a one-level battery ring: the bud pair for earbuds (and for an unknown kind). */
    fun ringPart(kind: Kind?): NotificationArt.Part = when (kind) {
        Kind.NECKBAND -> NotificationArt.Part.NECKBAND
        Kind.HEADPHONES -> NotificationArt.Part.HEADPHONES
        Kind.SPEAKER -> NotificationArt.Part.SPEAKER
        else -> NotificationArt.Part.BOTH
    }

    /** [full]: a model QuickBuds speaks to (models.json). Speakers get no loss warning. */
    fun features(kind: Kind?, full: Boolean): Int = when {
        full -> R.string.model_full
        kind == Kind.SPEAKER -> R.string.features_speaker
        else -> R.string.model_basic
    }
}
