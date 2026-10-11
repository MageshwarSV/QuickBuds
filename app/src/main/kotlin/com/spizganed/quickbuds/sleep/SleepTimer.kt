package com.spizganed.quickbuds.sleep

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.proximity.LossNotifier
import com.spizganed.quickbuds.ui.SleepTimerActivity
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * The sleep timer: at a set time the audio stops, then every connected Bluetooth audio device (earbuds, neckbands,
 * headphones, speakers) is disconnected, the way the home screen's Disconnect button does it ([PhoneAudio]); the
 * supported buds also lose QuickBuds' own link. Paused first, so the music does not move to the phone's speaker.
 * Disconnected earbuds and speakers switch off on their own after a few minutes. The buds' protocol has no
 * documented power-off, so none is sent.
 *
 * Scheduled with [AlarmManager]: exact when the user allows alarms (Android 12+), else inexact, which Android may
 * delay by some minutes in deep sleep; the screen says so. A reboot clears alarms: [rearm] sets it again when the
 * service or the app starts.
 */
object SleepTimer {

    private const val KEY_AT = "sleepAt"
    const val ACTION_FIRE = "com.spizganed.quickbuds.SLEEP_FIRE"
    const val ACTION_CANCEL = "com.spizganed.quickbuds.SLEEP_CANCEL"
    private const val CHANNEL = "QuickBuds_Sleep"
    private const val ID_ACTIVE = 6
    private const val ID_DONE = 7
    const val ID_FIRING = 8

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    /** When it goes off, or null when off (or the time has passed). */
    fun at(c: Context): Long? = prefs(c).getLong(KEY_AT, 0L).takeIf { it > System.currentTimeMillis() }

    /** Exact timing needs the user's leave on Android 12+. */
    fun exactAllowed(c: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        c.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    fun schedule(c: Context, atMs: Long) {
        prefs(c).edit().putLong(KEY_AT, atMs).apply()
        arm(c, atMs)
        PacketLogger.log("SLEEP: set, ${(atMs - System.currentTimeMillis()) / 60_000} min from now, exact=${exactAllowed(c)}")
        showActive(c, atMs)
    }

    fun cancel(c: Context) {
        prefs(c).edit().remove(KEY_AT).apply()
        c.getSystemService(AlarmManager::class.java)?.cancel(fireIntent(c))
        nm(c)?.cancel(ID_ACTIVE)
        PacketLogger.log("SLEEP: cancelled")
    }

    /** After a reboot, an app update or a start of the service: alarms do not survive those. */
    fun rearm(c: Context) {
        val at = prefs(c).getLong(KEY_AT, 0L)
        if (at <= 0L) return
        val now = System.currentTimeMillis()
        // Just past and not fired yet: an inexact alarm still on its way. Leave it.
        if (at <= now && now - at < INEXACT_WINDOW_MS + 60_000L) return
        if (at <= now) { prefs(c).edit().remove(KEY_AT).apply(); nm(c)?.cancel(ID_ACTIVE); return }
        arm(c, at)
        showActive(c, at)
    }

    private fun arm(c: Context, atMs: Long) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = fireIntent(c)
        runCatching {
            if (exactAllowed(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            // Without leave for exact alarms: the smallest window Android allows, so at most ~10 minutes late.
            // Was setAndAllowWhileIdle, which Android may push back by up to an hour.
            else am.setWindow(AlarmManager.RTC_WAKEUP, atMs, INEXACT_WINDOW_MS, pi)
        }.onFailure { PacketLogger.error("sleep timer", it) }
    }

    private fun fireIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(c, 0,
        Intent(c, SleepTimerReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * Time's up: take the audio focus (every player that respects it pauses and does not resume on its own) and send
     * a pause to the active media session for the rest; a moment later, disconnect every Bluetooth audio device and
     * let the focus go. [done] runs when that is over.
     */
    fun fire(c: Context, done: () -> Unit) {
        prefs(c).edit().remove(KEY_AT).apply()
        nm(c)?.cancel(ID_ACTIVE)
        val am = c.getSystemService(AudioManager::class.java)
        if (am == null) { done(); return }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { }
            .build()
        val focus = runCatching { am.requestAudioFocus(request) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        }
        PacketLogger.log("SLEEP: time is up, audio stopped (focus ${if (focus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "taken" else "refused"}, pause sent)")
        Handler(Looper.getMainLooper()).postDelayed({
            disconnectedAt = android.os.SystemClock.elapsedRealtime()
            // The supported buds: QuickBuds' own link too, as the Disconnect button does.
            runCatching {
                c.startService(Intent(c, com.spizganed.quickbuds.bluetooth.BudsService::class.java)
                    .setAction(com.spizganed.quickbuds.bluetooth.BudsService.ACTION_FORCE_DISCONNECT))
            }
            com.spizganed.quickbuds.bluetooth.PhoneAudio.disconnectAll(c) { n ->
                PacketLogger.log("SLEEP: disconnected $n Bluetooth audio device(s)")
                showDone(c)
                runCatching { am.abandonAudioFocusRequest(request) }
                done()
            }
        }, FOCUS_HOLD_MS)
    }

    private const val INEXACT_WINDOW_MS = 10 * 60_000L

    /** When the timer last disconnected the devices: those drops are the user's, not a loss. */
    @Volatile private var disconnectedAt = 0L

    /** True for [DROP_IS_USER_MS] after the timer disconnected: a link lost then raises no loss warning. */
    fun justDisconnected(): Boolean = android.os.SystemClock.elapsedRealtime() - disconnectedAt < DROP_IS_USER_MS

    private const val DROP_IS_USER_MS = 30_000L

    /** Long enough for the players to hear the loss and pause, before the devices go. */
    private const val FOCUS_HOLD_MS = 2_000L

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    private fun ensureChannel(c: Context, nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.sleep_channel),
            NotificationManager.IMPORTANCE_LOW).apply { description = c.getString(R.string.sleep_channel_sub) })
    }

    /** The foreground service's notification for the few seconds it takes. */
    fun firingNotification(c: Context): Notification {
        nm(c)?.let { ensureChannel(c, it) }
        return Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buds)
            .setContentTitle(c.getString(R.string.sleep_title))
            .setShowWhen(false)
            .build()
    }

    /** While it runs: when it goes off, with Cancel. */
    private fun showActive(c: Context, atMs: Long) {
        val nm = nm(c) ?: return
        if (!LossNotifier.canNotify(c, CHANNEL)) return
        ensureChannel(c, nm)
        val time = android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(atMs))
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buds)
            .setContentTitle(c.getString(R.string.sleep_title))
            .setContentText(c.getString(R.string.sleep_active, time))
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(PendingIntent.getActivity(c, 61, Intent(c, SleepTimerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(Notification.Action.Builder(null, c.getString(R.string.sleep_cancel),
                PendingIntent.getBroadcast(c, 62, Intent(c, SleepTimerReceiver::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            .build()
        runCatching { nm.notify(ID_ACTIVE, n) }
    }

    /** Done: audio stopped, devices disconnected; one tap to the Bluetooth settings to connect again. */
    private fun showDone(c: Context) {
        val nm = nm(c) ?: return
        if (!LossNotifier.canNotify(c, CHANNEL)) return
        ensureChannel(c, nm)
        val settings = PendingIntent.getActivity(c, 63, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buds)
            .setContentTitle(c.getString(R.string.sleep_done_title))
            .setContentText(c.getString(R.string.sleep_done_body))
            .setStyle(Notification.BigTextStyle().bigText(c.getString(R.string.sleep_done_body)))
            .setAutoCancel(true)
            .setContentIntent(settings)
            .addAction(Notification.Action.Builder(null, c.getString(R.string.sleep_bt_settings), settings).build())
            .build()
        runCatching { nm.notify(ID_DONE, n) }
    }
}

/** The alarm, the Cancel button, and a reboot or an update (alarms do not survive those). Not exported. */
class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SleepTimer.ACTION_FIRE -> {
                // In a short foreground service: Android 15+ gives audio focus only to one, or to the top app.
                val started = runCatching {
                    context.startForegroundService(Intent(context, SleepFireService::class.java))
                }.isSuccess
                if (!started) {
                    PacketLogger.log("SLEEP: no foreground service allowed, firing from the receiver")
                    val pending = goAsync()
                    SleepTimer.fire(context.applicationContext) { pending.finish() }
                }
            }
            SleepTimer.ACTION_CANCEL -> SleepTimer.cancel(context)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> SleepTimer.rearm(context)
        }
    }
}

/** A few seconds in the foreground while the timer stops the audio and disconnects: then it stops itself. */
class SleepFireService : android.app.Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = SleepTimer.firingNotification(this)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(SleepTimer.ID_FIRING, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else startForeground(SleepTimer.ID_FIRING, n)
        }.onFailure { PacketLogger.error("sleep timer service", it) }
        SleepTimer.fire(applicationContext) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
