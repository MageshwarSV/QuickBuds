package com.spizganed.quickbuds.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.DeviceCatalog
import com.spizganed.quickbuds.bluetooth.FastPairBattery
import com.spizganed.quickbuds.ui.DeviceKinds
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.ui.MainActivity
import com.spizganed.quickbuds.ui.ThemeRes
import com.spizganed.quickbuds.widget.WidgetStateStore

/**
 * A live battery card in the notification shade, like the system's own earbud card: the buds' name and a ring per
 * part, only for the buds out of the case (one bud out shows one, both show two) plus the case's charge when known.
 * Updated from every battery and wear report; gone when both buds are back in the case or the link drops.
 *
 * Silent by design (no sound, no vibration, no peek). Swiped away, it stays away until the buds next go back in
 * the case or reconnect. Buds that send no wear reports show both buds whenever connected.
 */
object BatteryCard {

    /** Settings › Battery card (default on). */
    const val KEY_ENABLED = "batteryCard"
    const val ACTION_DISMISSED = "com.spizganed.quickbuds.BATTERY_DISMISSED"
    /** On [ACTION_DISMISSED]: the address of other earbuds whose card was swiped ([showOther]). */
    const val EXTRA_ADDRESS = "address"

    const val CHANNEL = "QuickBuds_Battery"
    private const val ID = 4

    @Volatile var leftCharging = false
    @Volatile var rightCharging = false
    @Volatile var caseCharging = false


    /** What is on screen now, so an identical report posts nothing. */
    @Volatile private var shown: String? = null

    fun enabled(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    private fun out(status: Int) = status == 1 || status == 3 || status == 5 || status == 7

    /** The rings on the card: the buds that are out, and the case when it reports a level. */
    data class Rings(val left: Boolean, val case: Boolean, val right: Boolean)

    /** Null: no card. The buds are gone, or both are in the case. Without wear reports, a bud with a level is out. */
    fun rings(state: WidgetStateStore.State): Rings? {
        val noWearReports = state.leftStatus < 0 && state.rightStatus < 0
        val left = out(state.leftStatus) || (noWearReports && state.leftBattery > 0)
        val right = out(state.rightStatus) || (noWearReports && state.rightBattery > 0)
        if (!state.connected || (!left && !right)) return null
        return Rings(left, state.caseBattery > 0, right)
    }

    /** Shows, updates or hides the card from the stored state (connection, levels, wear). */
    fun update(context: Context, state: WidgetStateStore.State = WidgetStateStore.read(context)) {
        val rings = rings(state)
        if (rings == null) {
            // Back in the case, or gone: the only ways the card goes, with the setting.
            return hide(context)
        }
        if (!enabled(context)) return hide(context)
        val showLeft = rings.left
        val showRight = rings.right
        val showCase = rings.case
        val name = ModelCatalog.current(context)?.name
            ?: context.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE).getString(ModelCatalog.KEY_DEVICE_NAME, null)
            ?: context.getString(R.string.default_device_name)

        val signature = listOf(name, showLeft, showRight, showCase, state.leftBattery, state.rightBattery, state.caseBattery,
            leftCharging, rightCharging, caseCharging).joinToString()
        if (signature == shown) return

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, nm)
        fun views(layout: Int, ringDp: Float, big: Boolean) = RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.b_name, name)
            part(context, this, R.id.b_l, R.id.b_l_ring, R.id.b_l_pct, NotificationArt.Part.LEFT, showLeft,
                state.leftBattery, leftCharging, R.string.status_left, ringDp, big)
            part(context, this, R.id.b_r, R.id.b_r_ring, R.id.b_r_pct, NotificationArt.Part.RIGHT, showRight,
                state.rightBattery, rightCharging, R.string.status_right, ringDp, big)
            part(context, this, R.id.b_c, R.id.b_c_ring, R.id.b_c_pct, NotificationArt.Part.CASE, showCase,
                state.caseBattery, caseCharging, R.string.status_case, ringDp, big)
        }
        val summary = listOfNotNull(
            if (showLeft) level(context, R.string.status_left, state.leftBattery) else null,
            if (showCase) level(context, R.string.status_case, state.caseBattery) else null,
            if (showRight) level(context, R.string.status_right, state.rightBattery) else null
        ).joinToString(" · ")
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buds)
            .setContentTitle(name)
            .setContentText(summary)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(views(R.layout.notif_battery, 30f, big = false))
            .setCustomBigContentView(views(R.layout.notif_battery_big, 72f, big = true))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // Fixed while the buds are out, like the system's own earbuds card: "Clear all" leaves it, and a swipe
            // (Android 14+ allows one on any app's ongoing card) brings it straight back ([onDismissed]).
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(context, 1,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setDeleteIntent(PendingIntent.getService(context, 13,
                Intent(context, BudsService::class.java).setAction(ACTION_DISMISSED),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        runCatching { nm.notify(ID, n); shown = signature }.onFailure { PacketLogger.error("battery card", it) }
    }

    /**
     * The card was swiped away (or [address]'s card of other earbuds, [showOther]): it is fixed while the buds are
     * out, so it is posted again at once. The caller ([BudsService]) redraws right after this. Was: a swipe hid it until
     * the buds went back in the case.
     */
    fun onDismissed(address: String? = null) {
        if (address != null) otherShown.remove(address) else shown = null
    }

    // --- Earbuds of any other brand: one level for the pair, through standard Bluetooth ---

    /** What each other earbuds' card shows now. */
    private val otherShown = HashMap<String, String>()

    private fun otherId(address: String) = 1000 + (address.hashCode() and 0x3FF)

    /**
     * A card for earbuds outside `models.json` ([com.spizganed.quickbuds.bluetooth.AudioDevice]): their name, and
     * left, case, right from their Fast Pair broadcast when [parts] is trusted
     * ([com.spizganed.quickbuds.bluetooth.FastPairBattery.Picker]); else one ring, the level the headset reports.
     */
    fun showOther(
        context: Context, address: String, name: String, level: Int, parts: FastPairBattery.Levels? = null,
        kind: DeviceCatalog.Kind? = null
    ) {
        if (!enabled(context) || (level <= 0 && parts == null)) {
            return hideOther(context, address)
        }
        val signature = "$name $level $parts $kind"
        if (otherShown[address] == signature) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, nm)
        fun views(layout: Int, ringDp: Float, big: Boolean) = RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.b_name, name)
            if (parts != null) {
                part(context, this, R.id.b_l, R.id.b_l_ring, R.id.b_l_pct, NotificationArt.Part.LEFT, parts.left >= 0,
                    parts.left, parts.leftCharging, R.string.status_left, ringDp, big)
                part(context, this, R.id.b_c, R.id.b_c_ring, R.id.b_c_pct, NotificationArt.Part.CASE, parts.case >= 0,
                    parts.case, parts.caseCharging, R.string.status_case, ringDp, big)
                part(context, this, R.id.b_r, R.id.b_r_ring, R.id.b_r_pct, NotificationArt.Part.RIGHT, parts.right >= 0,
                    parts.right, parts.rightCharging, R.string.status_right, ringDp, big)
            } else {
                part(context, this, R.id.b_l, R.id.b_l_ring, R.id.b_l_pct, DeviceKinds.ringPart(kind), true,
                    level, false, oneLabel(kind), ringDp, big)
                setViewVisibility(R.id.b_c, View.GONE)
                setViewVisibility(R.id.b_r, View.GONE)
            }
        }
        val summary = if (parts == null) level(context, oneLabel(kind), level) else listOfNotNull(
            if (parts.left >= 0) level(context, R.string.status_left, parts.left) else null,
            if (parts.case >= 0) level(context, R.string.status_case, parts.case) else null,
            if (parts.right >= 0) level(context, R.string.status_right, parts.right) else null
        ).joinToString(" · ")
        val id = otherId(address)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buds)
            .setContentTitle(name)
            .setContentText(summary)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(views(R.layout.notif_battery, 30f, big = false))
            .setCustomBigContentView(views(R.layout.notif_battery_big, 72f, big = true))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setDeleteIntent(PendingIntent.getService(context, id,
                Intent(context, BudsService::class.java).setAction(ACTION_DISMISSED).putExtra(EXTRA_ADDRESS, address),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        runCatching { nm.notify(id, n); otherShown[address] = signature }.onFailure { PacketLogger.error("battery card", it) }
    }

    /** The label under a one-level ring: the kind of device ("Neckband", "Speaker"), "Earbuds" when TWS or unknown. */
    private fun oneLabel(kind: DeviceCatalog.Kind?) =
        if (kind == null || kind == DeviceCatalog.Kind.TWS) R.string.battery_label_buds else DeviceKinds.label(kind)!!

    /** Every other-brand card on screen, also ones a killed process left: a new service tracks none of them. */
    fun hideAllOther(context: Context) {
        otherShown.clear()
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.activeNotifications.filter { it.id in 1000..2023 && it.notification.channelId == CHANNEL }.forEach { nm.cancel(it.id) } }
    }

    /** The other earbuds disconnected, or the card was turned off. */
    fun hideOther(context: Context, address: String) {
        otherShown.remove(address)
        context.getSystemService(NotificationManager::class.java)?.cancel(otherId(address))
    }

    /** Cancels even with nothing recorded: a card can outlive the process that posted it. */
    fun hide(context: Context) {
        shown = null
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    private fun level(context: Context, label: Int, level: Int) = context.getString(label) + " " + (if (level > 0) "$level%" else "—")

    private fun part(
        context: Context, v: RemoteViews, group: Int, ring: Int, pct: Int, part: NotificationArt.Part, show: Boolean,
        level: Int, charging: Boolean, label: Int, ringDp: Float, big: Boolean
    ) {
        v.setViewVisibility(group, if (show) View.VISIBLE else View.GONE)
        if (!show) return
        v.setImageViewBitmap(ring, NotificationArt.batteryRing(context, part, level, charging, ringDp))
        val text = if (level > 0) "$level%" else "—"
        v.setTextViewText(pct, if (big) context.getString(label) + " " + text else text)
        v.setContentDescription(ring, level(context, label, level))
    }

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.battery_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.battery_channel_sub)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }
}
