package com.spizganed.quickbuds.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.DeviceCatalog
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.ui.DeviceKinds
import com.spizganed.quickbuds.notify.LowBattery.Change
import com.spizganed.quickbuds.notify.LowBattery.Part
import com.spizganed.quickbuds.proximity.LossNotifier
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * "Charge it" cards ([LowBattery]): which part is low (left bud, right bud, both, the case) and what to do, on an
 * amber card with the low part's battery ring. One card lists every low part; it updates quietly as levels move
 * and goes once nothing is low. Its own channel: it sounds, but does not peek over what you are doing.
 */
object LowBatteryAlert {

    /** Settings › Low battery alerts (default on). */
    const val KEY_ENABLED = "lowBatteryAlerts"

    const val CHANNEL = "QuickBuds_LowBattery"
    private const val ID = 5
    private val logic = LowBattery()

    fun enabled(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    /** A battery report: levels are null for parts it leaves out (the case with the lid closed). */
    fun onBattery(context: Context, left: Int?, case: Int?, right: Int?, chargingLeft: Boolean, chargingCase: Boolean, chargingRight: Boolean) {
        if (!enabled(context)) return
        val changes = listOfNotNull(
            left?.let { logic.onLevel(Part.LEFT, it, chargingLeft) },
            case?.let { logic.onLevel(Part.CASE, it, chargingCase) },
            right?.let { logic.onLevel(Part.RIGHT, it, chargingRight) }
        )
        val change = changes.maxByOrNull { it.ordinal } ?: Change.NONE
        if (change == Change.NONE) return
        if (logic.low.isEmpty()) return clear(context)
        if (change == Change.ALERT) PacketLogger.log("LOW BATTERY: ${logic.low}")
        post(context, alert = change == Change.ALERT)
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    /**
     * The alerts were turned off: every card goes, and what was said is forgotten. Was: only the buds' card went,
     * and a part that charged while the alerts were off never alerted again.
     */
    fun turnedOff(context: Context) {
        clear(context)
        other.keys.toList().forEach { clearOther(context, it) }
        logic.reset()
        other.clear()
    }

    // --- Earbuds of any other brand: one level for the pair ---

    private val other = HashMap<String, LowBattery>()

    private fun otherId(address: String) = 3000 + (address.hashCode() and 0x3FF)

    /**
     * A level for a device outside `models.json`: "Earbuds battery low" for earbuds, "<name> battery low" for a
     * neckband, headphones or a speaker; with the name and the level.
     */
    fun onOther(context: Context, address: String, name: String, level: Int, kind: DeviceCatalog.Kind? = null) {
        if (!enabled(context)) return
        val logic = other.getOrPut(address) { LowBattery() }
        val change = logic.onLevel(Part.LEFT, level, charging = false)
        if (change == Change.NONE) return
        if (logic.low.isEmpty()) return clearOther(context, address)
        if (change == Change.ALERT) PacketLogger.log("LOW BATTERY: other earbuds at $level%")
        val id = otherId(address)
        val earbuds = kind == null || kind == DeviceCatalog.Kind.TWS
        val title = if (earbuds) context.getString(R.string.low_title_buds) else context.getString(R.string.low_title_device, name)
        val body = "$name $level% — " + context.getString(if (earbuds) R.string.low_hint_buds else R.string.low_hint_charge)
        val part = DeviceKinds.ringPart(kind)
        context.getSystemService(NotificationManager::class.java)?.let { ensureChannel(context, it) }
        LossNotifier.post(
            context, id, title, body, R.drawable.notif_bg_low,
            NotificationArt.batteryRing(context, part, level, false, 40f),
            NotificationArt.batteryRing(context, part, level, false, 64f),
            listOf(LossNotifier.gotIt(context, id)), change == Change.ALERT, CHANNEL
        )
    }

    fun clearOther(context: Context, address: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(otherId(address))
    }

    private fun post(context: Context, alert: Boolean) {
        val low = logic.low
        val buds = low.keys.filter { it != Part.CASE }
        val title = context.getString(when {
            buds.size == 2 -> R.string.low_title_buds
            buds.size == 1 && buds[0] == Part.LEFT -> R.string.low_title_left
            buds.size == 1 -> R.string.low_title_right
            else -> R.string.low_title_case
        })
        // Left, case, right: the order of the battery card.
        val levels = low.entries.sortedBy { listOf(Part.LEFT, Part.CASE, Part.RIGHT).indexOf(it.key) }.joinToString(" · ") { (part, level) -> context.getString(label(part)) + " " + level + "%" }
        val hints = listOfNotNull(
            if (buds.isNotEmpty()) context.getString(R.string.low_hint_buds) else null,
            if (Part.CASE in low) context.getString(R.string.low_hint_case) else null
        )
        val body = (listOf(levels) + hints).joinToString(" — ")
        // The ring of the lowest part.
        val (part, level) = low.entries.minByOrNull { it.value }!!.toPair()
        val artPart = when (part) {
            Part.LEFT -> NotificationArt.Part.LEFT
            Part.RIGHT -> NotificationArt.Part.RIGHT
            Part.CASE -> NotificationArt.Part.CASE
        }
        context.getSystemService(NotificationManager::class.java)?.let { ensureChannel(context, it) }
        LossNotifier.post(
            context, ID, title, body, R.drawable.notif_bg_low,
            NotificationArt.batteryRing(context, artPart, level, false, 40f),
            NotificationArt.batteryRing(context, artPart, level, false, 64f),
            listOf(LossNotifier.gotIt(context, ID)), alert, CHANNEL
        )
    }

    private fun label(part: Part) = when (part) {
        Part.LEFT -> R.string.status_left
        Part.RIGHT -> R.string.status_right
        Part.CASE -> R.string.status_case
    }

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.low_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.low_channel_sub)
            }
        )
    }
}
