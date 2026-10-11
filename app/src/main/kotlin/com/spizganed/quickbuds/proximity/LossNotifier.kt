package com.spizganed.quickbuds.proximity

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsDevice
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.notify.NotificationArt
import com.spizganed.quickbuds.ui.FindNearbyActivity
import com.spizganed.quickbuds.ui.MainActivity

/**
 * The loss warning ("You might be about to lose your headphones") and the case reminder ("Only one earbud is in
 * the case"), as designed cards: a gradient background, a picture, bold title and body (`notif_alert*.xml`), and
 * quick actions under it. One high-importance channel, so both sound and peek; each has its own id, so a second
 * warning replaces the first instead of stacking.
 */
object LossNotifier {

    const val CHANNEL = "QuickBuds_Loss"
    private const val ID = 2
    private const val CASE_ID = 3

    /** [BudsService] actions behind the cards' buttons. */
    const val ACTION_FIND = "com.spizganed.quickbuds.LOSS_FIND"
    const val EXTRA_ON = "on"
    /** On [ACTION_FIND]: only switch the tone, the card is already gone. */
    const val EXTRA_SILENT = "silent"
    const val ACTION_DISMISS = "com.spizganed.quickbuds.ALERT_DISMISS"
    const val EXTRA_ID = "id"

    /** The supported buds' loss warning on screen: whether the link was lost, and whether its find tone is playing. */
    @Volatile private var shownLinkLost: Boolean? = null
    @Volatile var ringing = false
        private set
    /**
     * Find nearby is on screen and owns the tone: the card clearing (the buds recovered) must not silence what the user
     * started there. The screen stops its own tone when it closes.
     */
    @Volatile var toneHeldByFindScreen = false

    /** When each card last sounded, by owner ("" for the supported buds). */
    private val lastAlert = HashMap<String, Long>()

    /** A card sounds at most once per [ALERT_COOLDOWN_MS]: a link that drops and comes back at the edge of range. */
    private const val ALERT_COOLDOWN_MS = 60_000L
    /** Battery cards 1000, low-battery cards 3000, loss cards 5000: each + 0..1023, so no range overlaps. */
    private const val OTHER_BASE = 5000

    /** The card's id: [ID] for the supported buds, one per address for other devices. */
    fun idFor(key: String?) = if (key == null) ID else OTHER_BASE + (key.hashCode() and 0x3FF)

    /** True for a loss card of another device: its "I have them" goes to that device's monitor. */
    fun isOtherLossId(id: Int) = id in OTHER_BASE until OTHER_BASE + 0x400

    /** Android 13+ needs the runtime permission; the user can also block the app or this channel. */
    fun canNotify(context: Context, channelId: String = CHANNEL): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) return false
        val channel = nm.getNotificationChannel(channelId)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * [linkLost]: the link dropped while the signal was weak, rather than the signal getting weak. [key]: null for
     * the supported buds, whose card offers Play sound while the link is up ([ringing] turns it into Stop); another
     * device's address for its own card, without Play sound. Within [ALERT_COOLDOWN_MS] of the last sound for the
     * same card, it updates quietly.
     */
    fun warn(context: Context, linkLost: Boolean, ringing: Boolean = false, quiet: Boolean = false, key: String? = null) {
        val id = idFor(key)
        val canFind = key == null
        if (canFind) {
            shownLinkLost = linkLost
            this.ringing = ringing && !linkLost
        }
        val title = context.getString(R.string.loss_title)
        val body = context.getString(if (linkLost) R.string.loss_body_lost else R.string.loss_body)
        val actions = mutableListOf<Notification.Action>()
        if (canFind && !linkLost) {
            val on = !this.ringing
            actions += action(context, if (on) R.string.find_play else R.string.find_stop, 10,
                Intent(context, BudsService::class.java).setAction(ACTION_FIND).putExtra(EXTRA_ON, on))
        }
        // Find nearby: the search screen (distance, radar, arrow). Also once the link is lost: the buds' own Fast Pair
        // broadcast still says where they are, matched by their last battery levels.
        (key ?: BudsDevice.find(context)?.address)?.let { address ->
            actions += Notification.Action.Builder(null, context.getString(R.string.find_nav_title),
                PendingIntent.getActivity(context, 200_000 + id,
                    FindNearbyActivity.intent(context, address,
                        if (key == null) com.spizganed.quickbuds.protocol.ModelCatalog.current(context)?.name
                        else com.spizganed.quickbuds.bluetooth.ConnectedDevices.all().firstOrNull { it.address == key }?.name,
                        // The levels now: after a drop they are cleared, and the screen matches the broadcast by them.
                        if (key == null) com.spizganed.quickbuds.widget.WidgetStateStore.read(context).let {
                            intArrayOf(it.leftBattery, it.rightBattery, it.caseBattery)
                        } else com.spizganed.quickbuds.bluetooth.ConnectedDevices.all().firstOrNull { it.address == key }
                            ?.level?.let { intArrayOf(it) }
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        }
        // One request code per card: else the last card's extras overwrite the others' "I have them".
        actions += action(context, R.string.loss_action_found, 100_000 + id,
            Intent(context, BudsService::class.java).setAction(ACTION_DISMISS).putExtra(EXTRA_ID, id))
        val now = SystemClock.elapsedRealtime()
        val owner = key ?: ""
        // The cooldown only quiets an update of a card still on screen; a new card always sounds.
        val loud = !quiet && (!isShowing(context, id) || synchronized(lastAlert) {
            lastAlert[owner]?.let { now - it >= ALERT_COOLDOWN_MS } ?: true
        })
        if (loud) synchronized(lastAlert) { lastAlert[owner] = now }
        post(context, id, title, body, R.drawable.notif_bg_loss,
            NotificationArt.lossArt(context, 40f), NotificationArt.lossArt(context, 64f), actions, alert = loud)
    }

    /** The find tone was switched from the card: the same warning with the other button. */
    fun setRinging(context: Context, on: Boolean) {
        val linkLost = shownLinkLost ?: return
        warn(context, linkLost, on, quiet = true)
    }

    /** Removes the loss card of [key] (null: the supported buds). */
    fun clear(context: Context, key: String? = null) {
        if (key != null) {
            context.getSystemService(NotificationManager::class.java)?.cancel(idFor(key))
            return
        }
        // Recovered or dismissed while the find tone plays: stop it too, unless Find nearby holds it.
        if (toneHeldByFindScreen) { context.getSystemService(NotificationManager::class.java)?.cancel(ID); shownLinkLost = null; return }
        if (ringing) runCatching {
            context.startService(Intent(context, BudsService::class.java).setAction(ACTION_FIND).putExtra(EXTRA_ON, false)
                .putExtra(EXTRA_SILENT, true))
        }
        shownLinkLost = null
        ringing = false
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    /** "Only one earbud is in the case" ([CaseReminder]), naming the bud left out. */
    fun caseReminder(context: Context, outside: CaseReminder.Side) {
        val left = outside == CaseReminder.Side.LEFT
        val body = context.getString(if (left) R.string.case_reminder_left else R.string.case_reminder_right)
        val ok = gotIt(context, CASE_ID)
        post(context, CASE_ID, context.getString(R.string.case_reminder_title), body, R.drawable.notif_bg_case,
            NotificationArt.caseArt(context, left, 40f), NotificationArt.caseArt(context, left, 64f), listOf(ok), alert = true)
    }

    fun clearCaseReminder(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(CASE_ID)
    }

    /** Cancels the card [id] (a card's dismiss button). */
    fun dismiss(context: Context, id: Int) = if (id == ID) clear(context)
        else context.getSystemService(NotificationManager::class.java)?.cancel(id)

    /** A "Got it" button that closes the card [id]. */
    fun gotIt(context: Context, id: Int): Notification.Action = action(context, R.string.report_later, 20 + id,
        Intent(context, BudsService::class.java).setAction(ACTION_DISMISS).putExtra(EXTRA_ID, id))

    private fun action(context: Context, label: Int, request: Int, intent: Intent): Notification.Action =
        Notification.Action.Builder(null, context.getString(label),
            PendingIntent.getService(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ).build()

    private fun card(context: Context, layout: Int, background: Int, art: Bitmap, title: String, body: String) =
        RemoteViews(context.packageName, layout).apply {
            setInt(R.id.alert_root, "setBackgroundResource", background)
            setImageViewBitmap(R.id.alert_art, art)
            setTextViewText(R.id.alert_title, title)
            setTextViewText(R.id.alert_body, body)
        }

    /**
     * Posts a card ([notif_alert] layouts) on [channelId]. [alert] false updates it quietly (a button press), true
     * sounds and peeks as the channel allows. Another channel than the loss one must exist already.
     */
    fun post(
        context: Context, id: Int, title: String, body: String, background: Int,
        small: Bitmap, big: Bitmap, actions: List<Notification.Action>, alert: Boolean, channelId: String = CHANNEL
    ) {
        if (!canNotify(context, channelId)) {
            PacketLogger.log("LOSS: \"$title\" not shown, notifications are off for QuickBuds")
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // A quiet update never brings back a card the user closed. Was: a closed low-battery card came back, with
        // sound, at every 1 % step (only-alert-once holds only while the card is on screen).
        if (!alert && !isShowing(context, id)) return
        if (channelId == CHANNEL) ensureChannel(context, nm)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_buds)
            // Title and text stay for the lock screen, watches and screen readers; the cards are what shows.
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(card(context, R.layout.notif_alert, background, small, title, body))
            .setCustomBigContentView(card(context, R.layout.notif_alert_big, background, big, title, body))
            .setCustomHeadsUpContentView(card(context, R.layout.notif_alert, background, small, title, body))
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
        actions.forEach { builder.addAction(it) }
        runCatching { nm.notify(id, builder.build()) }.onFailure { PacketLogger.error("loss notification", it) }
    }

    /** True while the card [id] is on screen. */
    fun isShowing(context: Context, id: Int): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java)?.activeNotifications?.any { it.id == id } == true
    }.getOrDefault(false)

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.loss_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.loss_channel_sub)
            }
        )
    }
}
