package com.spizganed.quickbuds.notify

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsDevice
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.ui.MainActivity
import com.spizganed.quickbuds.ui.ThemeRes
import com.spizganed.quickbuds.usage.CaseCharge
import com.spizganed.quickbuds.widget.WidgetStateStore
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Charge reminder (Settings): every day at the time the user sets, a notification when the left bud, the right
 * bud or the case is below the level the user sets (60% by default). The levels are the last ones the buds
 * reported. Off by default. An inexact daily alarm, set again after a reboot or an update.
 */
object ChargeReminder {
    const val KEY_ENABLED = "chargeReminder"
    const val KEY_MINUTE = "chargeReminderMinute"
    const val KEY_BELOW = "chargeReminderBelow"
    const val DEFAULT_MINUTE = 21 * 60
    const val DEFAULT_BELOW = 60
    val LEVELS = listOf(20, 30, 40, 50, 60, 70, 80, 90)

    private const val CHANNEL = "QuickBuds_ChargeReminder"
    private const val ID = 10
    /** Android may move an inexact alarm: within this window after the time. */
    private const val WINDOW_MS = 10 * 60_000L

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false)
    fun minuteOfDay(c: Context) = prefs(c).getInt(KEY_MINUTE, DEFAULT_MINUTE).coerceIn(0, 24 * 60 - 1)
    fun below(c: Context) = prefs(c).getInt(KEY_BELOW, DEFAULT_BELOW).takeIf { it in LEVELS } ?: DEFAULT_BELOW

    fun set(c: Context, enabled: Boolean = enabled(c), minute: Int = minuteOfDay(c), below: Int = below(c)) {
        prefs(c).edit().putBoolean(KEY_ENABLED, enabled).putInt(KEY_MINUTE, minute).putInt(KEY_BELOW, below).apply()
        rearm(c)
    }

    // --- Pure rules ---

    /** One part and its level. */
    enum class Part { LEFT, RIGHT, CASE }

    /** The parts below [below]%; unknown levels (-1) do not count. Pure. */
    fun low(left: Int, right: Int, case: Int, below: Int): List<Pair<Part, Int>> =
        listOf(Part.LEFT to left, Part.RIGHT to right, Part.CASE to case).filter { (_, l) -> l in 0 until below }

    /** The next time at [minute] of the day after [nowMs] in [zone]: today if still ahead, else tomorrow. Pure. */
    fun nextAt(nowMs: Long, minute: Int, zone: ZoneId): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        var at: ZonedDateTime = now.toLocalDate().atStartOfDay(zone).plusMinutes(minute.toLong())
        if (!at.isAfter(now)) at = now.toLocalDate().plusDays(1).atStartOfDay(zone).plusMinutes(minute.toLong())
        return at.toInstant().toEpochMilli()
    }

    // --- The alarm ---

    private fun fireIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(c, 0,
        Intent(c, ChargeReminderReceiver::class.java).setAction(ACTION_FIRE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Sets the next alarm, or cancels it when off. After a setting change, a reboot and each reminder. */
    fun rearm(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = fireIntent(c)
        am.cancel(pi)
        if (!enabled(c)) return
        val at = nextAt(System.currentTimeMillis(), minuteOfDay(c), ZoneId.systemDefault())
        runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pi) }.onFailure { PacketLogger.error("charge reminder alarm", it) }
    }

    /** The time: the last levels, and a notification for the low parts. */
    fun check(c: Context) {
        val st = WidgetStateStore.read(c)
        val snap = BudsDevice.find(c)?.address?.let { CaseCharge.load(c, it).snap }
        // The levels now; a part the store no longer has comes from the last report with the case known.
        val left = st.leftBattery.takeIf { it >= 0 } ?: snap?.left ?: -1
        val right = st.rightBattery.takeIf { it >= 0 } ?: snap?.right ?: -1
        val case = st.caseBattery.takeIf { it >= 0 } ?: snap?.case ?: -1
        val below = below(c)
        val parts = low(left, right, case, below)
        if (left < 0 && right < 0 && case < 0) return PacketLogger.log("CHARGE REMINDER: no levels known")
        if (parts.isEmpty()) return PacketLogger.log("CHARGE REMINDER: all at or above $below%")
        PacketLogger.log("CHARGE REMINDER: ${parts.joinToString { "${it.first.name.lowercase()} ${it.second}%" }} below $below%")
        notify(c, parts, below)
    }

    private fun notify(c: Context, parts: List<Pair<Part, Int>>, below: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return PacketLogger.log("CHARGE REMINDER: not shown, notifications are off for QuickBuds")
        }
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.cr_title), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val names = parts.joinToString(", ") { (p, l) ->
            c.getString(R.string.cr_part, c.getString(when (p) {
                Part.LEFT -> R.string.status_left
                Part.RIGHT -> R.string.status_right
                Part.CASE -> R.string.status_case
            }), l)
        }
        val open = PendingIntent.getActivity(c, 0,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = c.getString(R.string.cr_body, names, below)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(c, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(c)
        val n = builder.setSmallIcon(R.drawable.ic_stat_buds)
            .setContentTitle(c.getString(R.string.cr_notif_title))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(ID, n) }.onFailure { PacketLogger.error("charge reminder", it) }
    }

    const val ACTION_FIRE = "com.spizganed.quickbuds.CHARGE_REMINDER"
}

/** The daily alarm, and a reboot or an app update, which clear alarms. */
class ChargeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ChargeReminder.ACTION_FIRE && ChargeReminder.enabled(context)) ChargeReminder.check(context)
        ChargeReminder.rearm(context)
    }
}
