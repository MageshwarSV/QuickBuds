package com.spizganed.quickbuds.findphone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.ui.LiveScreen

/**
 * Rings this phone once for Find My Phone: the chosen sound ([FindPhone.Sound]), from the phone's own speaker.
 * Android 12+: the call stream on the loudspeaker route ([voiceAttributes]), which Silent, Vibrate and Do Not
 * Disturb do not mute. Older Android, or during a call: the alarm channel. For the time it rings:
 *  - that stream's volume goes to full and comes back after;
 *  - with Do Not Disturb access (the user allows it in Android's settings), a Do Not Disturb that would block
 *    alarms lets alarms through, and comes back after. Without the access it stays as it is (said in the screen);
 *  - other sound pauses (audio focus).
 * It never loops, and only one plays at a time. A notification has Stop. Main thread.
 */
object FinderAlarm {
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private var savedFilter = -1
    private var focus: AudioFocusRequest? = null
    /** No new alarm this soon after one stopped: a last tap must not ring it again. */
    private var stoppedAt = 0L

    val ringing: Boolean get() = player != null

    private fun log(msg: String) = PacketLogger.log("FIND PHONE: $msg")

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** QuickBuds' own finder sound. */
    fun defaultUri(c: Context): Uri = Uri.parse("android.resource://${c.packageName}/${R.raw.finder}")

    /** The phone's ringtone now (it follows the user's changes), or null when none is set. */
    fun ringtoneUri(c: Context): Uri? = runCatching { RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_RINGTONE) }.getOrNull()

    /** The sound to play: [sound]'s, falling back to QuickBuds' own when it is missing. */
    fun uriFor(c: Context, sound: FindPhone.Sound): Uri = when (sound) {
        FindPhone.Sound.RINGTONE -> ringtoneUri(c) ?: defaultUri(c).also { log("no ringtone set: QuickBuds' sound") }
        FindPhone.Sound.FINDER -> FindPhone.customUri(c) ?: defaultUri(c)
    }

    /**
     * Rings once with [uri] ([why] for the log). [loud]: full alarm volume and Do Not Disturb let through (the
     * alarm, a test of it); false only previews the sound as it is.
     */
    fun ring(context: Context, uri: Uri, why: String, loud: Boolean = true) {
        val c = context.applicationContext
        if (player != null) return
        if (SystemClock.elapsedRealtime() - stoppedAt < COOLDOWN_MS) return
        val am = c.getSystemService(AudioManager::class.java) ?: return
        // Android 12+, no call on: the loudspeaker call route ([speakerRoute]). Else the alarm channel.
        val speaker = if (loud && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.mode == AudioManager.MODE_NORMAL)
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } else null
        val attrs = if (speaker != null) voiceAttributes else attributes
        val mp = MediaPlayer()
        runCatching {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(c, uri)
            mp.isLooping = false
            // The phone's speaker, not the earbuds: the phone is what has to be found.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { mp.setPreferredDevice(it) }
            }
            mp.setOnCompletionListener { stop(c, "played once") }
            mp.setOnErrorListener { _, what, extra ->
                log("cannot play ($what/$extra)")
                stop(c, "error")
                // A picked file that does not play: QuickBuds' own sound instead.
                if (uri != defaultUri(c)) main.post { stoppedAt = 0L; ring(c, defaultUri(c), "fallback", loud) }
                true
            }
            mp.prepare()
        }.onFailure {
            log("cannot open the sound (${it.javaClass.simpleName})")
            runCatching { mp.release() }
            if (uri != defaultUri(c)) ring(c, defaultUri(c), "fallback", loud)
            return
        }
        player = mp
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build()
            .also { runCatching { am.requestAudioFocus(it) } }
        if (loud) notify(c)
        LiveScreen.changed()
        if (speaker != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { am.setCommunicationDevice(speaker) }.getOrDefault(false)) {
            routedToSpeaker = true
            // The route takes a moment: the volume then belongs to the loudspeaker.
            main.postDelayed({
                if (player !== mp) return@postDelayed
                full(am, AudioManager.STREAM_VOICE_CALL)
                mp.start()
                log("ringing ($why, loudspeaker call route)")
            }, ROUTE_MS)
            return
        }
        if (loud) louder(c, am)
        mp.start()
        log("ringing ($why${if (loud) ", alarm channel" else ", preview"})")
    }

    /**
     * Android keeps one volume per output: with earbuds connected, the alarm volume that can be set is the
     * earbuds' one, and the phone speaker's alarm volume stays as it is (1 of 16 on the developer's phone,
     * 2026-11-23). The call stream routed to the loudspeaker has the speaker's own volume, which can be set while
     * it rings; Silent, Vibrate and Do Not Disturb do not mute it. Was: the alarm channel only, almost silent.
     */
    private val voiceAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var routedToSpeaker = false
    private var savedStream = AudioManager.STREAM_ALARM

    /** [stream] at full volume, the old volume saved for [stop]. */
    private fun full(am: AudioManager, stream: Int) {
        runCatching {
            savedVolume = am.getStreamVolume(stream)
            savedStream = stream
            am.setStreamVolume(stream, am.getStreamMaxVolume(stream), 0)
        }.onFailure { savedVolume = -1; log("volume not changed (${it.javaClass.simpleName})") }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Full alarm volume, and alarms through Do Not Disturb when allowed: both saved, put back by [stop]. */
    private fun louder(c: Context, am: AudioManager) {
        full(am, AudioManager.STREAM_ALARM)
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val filter = nm.currentInterruptionFilter
        val blocksAlarms = filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
            (filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                (nm.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) == 0)
        if (!blocksAlarms) return
        if (!nm.isNotificationPolicyAccessGranted) return log("Do Not Disturb blocks alarms, and QuickBuds has no access to it")
        runCatching {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            savedFilter = filter
            log("Do Not Disturb lets alarms through while it rings")
        }
    }

    /** Stops at once ([why] for the log), and puts back the volume and Do Not Disturb. */
    fun stop(context: Context, why: String) {
        val c = context.applicationContext
        val mp = player ?: return
        player = null
        runCatching { mp.stop() }
        runCatching { mp.release() }
        val am = c.getSystemService(AudioManager::class.java)
        focus?.let { f -> runCatching { am?.abandonAudioFocusRequest(f) } }
        focus = null
        if (savedVolume >= 0) runCatching { am?.setStreamVolume(savedStream, savedVolume, 0) }
        savedVolume = -1
        // The call route back to normal, after the volume (the volume belonged to the loudspeaker).
        if (routedToSpeaker && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { am?.clearCommunicationDevice() }
        routedToSpeaker = false
        // The Do Not Disturb setting from before, as Android's own constant.
        val before = when (savedFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> NotificationManager.INTERRUPTION_FILTER_NONE
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            NotificationManager.INTERRUPTION_FILTER_ALL -> NotificationManager.INTERRUPTION_FILTER_ALL
            else -> null
        }
        if (before != null) runCatching { c.getSystemService(NotificationManager::class.java)?.setInterruptionFilter(before) }
        savedFilter = -1
        stoppedAt = SystemClock.elapsedRealtime()
        c.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        log("stopped ($why)")
        LiveScreen.changed()
    }

    private fun notify(c: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.fp_title), NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null) })
        }
        val stop = PendingIntent.getService(c, NOTIFICATION_ID,
            Intent(c, BudsService::class.java).setAction(BudsService.ACTION_FIND_PHONE_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(c, NOTIFICATION_ID,
            Intent(c, com.spizganed.quickbuds.ui.FindPhoneActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(c, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(c)
        val n = b.setSmallIcon(R.drawable.ic_stat_loop)
            .setContentTitle(c.getString(R.string.fp_title))
            .setContentText(c.getString(R.string.fp_notif_body))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, c.getString(R.string.fp_stop), stop).build())
            .setOngoing(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    private const val CHANNEL = "QuickBuds_FindPhone"
    private const val NOTIFICATION_ID = 11
    private const val COOLDOWN_MS = 3_000L
    private const val ROUTE_MS = 300L
}
