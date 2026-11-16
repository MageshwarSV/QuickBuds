package com.spizganed.quickbuds.music

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.calls.BikeMode
import com.spizganed.quickbuds.calls.MusicCommands
import com.spizganed.quickbuds.calls.VoiceAnswer
import com.spizganed.quickbuds.ui.ThemeRes
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Voice music's listener, in [BudsService]: while Bike mode is on, Vosk listens offline through the earbuds'
 * microphone for "Hey Jarvis" + a command ([MusicCommands.grammar]), and runs it in the chosen music app. "Hey
 * Jarvis" alone beeps, then a command alone counts for [WINDOW_MS]. While a call rings, "Hey Jarvis, cut the call"
 * rejects it (voice answer, while it asks, has the microphone instead). During the call it is off: Android silences
 * it. It starts when the earbuds connect for calls and stops when they go.
 *
 * Vosk records continuously: no start tone. Was: Android's speech engine, which played a tone at each of its
 * 8-second listens (a "toing" every 10 to 15 s), and returned no words when fed QuickBuds' own recording.
 * By the developer's choice (2026-10-19), knowing the cost: always the earbuds' microphone, the only one that hears
 * on a bike. It puts the earbuds in call mode, so music plays in call quality (muffled): Bluetooth cannot do both at
 * once. Was: the phone's microphone while music played; it cannot hear from a pocket or a bag. Never online.
 */
class VoiceMusicLoop(context: Context) {

    private val c = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val am = c.getSystemService(AudioManager::class.java)
    private var running = false
    /** A call rings: "Hey Jarvis, cut the call" rejects it. */
    private var ringing = false
    /** The ringing call's number (with the call log permission): "cut the call" may text it ([AutoReply]). */
    private var ringingNumber: String? = null
    /**
     * In a call: not listening. Android silences other apps' microphones during a call (2026-10-19, the phone's own
     * audio log: "pack:com.spizganed.quickbuds … silenced:true"), so "cut the call" cannot be heard then.
     */
    private var inCall = false
    /** "Hey Jarvis" alone was heard: a command alone counts until this time. */
    private var awaitUntil = 0L
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var listener: Listener? = null
    /** Results that were no command, logged up to REPORT_MAX per run, to tune the phrases. */
    private var reported = 0
    /** Each start and stop counts up: a model load or a result from an earlier start is dropped. */
    private var generation = 0
    private var destroyed = false
    /** Listening errors in a row: each retry waits longer. */
    private var failures = 0
    private var tone: android.media.ToneGenerator? = null
    /** The last "not listening" reason logged: the same one is not logged again. */
    private var lastWhy: String? = null

    private fun log(msg: String) = PacketLogger.log("VOICE MUSIC: $msg")

    /** Why it must not listen now, or null. In the order a settings screen would show the first missing thing. */
    private fun blocker(): String? = when {
        destroyed -> "service ends"
        !VoiceMusic.switchedOn(c) || !BikeMode.isOn(c) -> "off"
        VoiceMusic.app(c) == null || !VoiceMusic.accessAllowed(c) || !VoskModel.ready(c) -> "not set up"
        !BudsService.microphoneReady -> "microphone not ready"
        c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> "no microphone permission"
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> "Android too old"
        inCall -> "in a call: Android gives the microphone to the call only"
        VoiceAnswer.asking -> "voice answer asks"
        else -> null
    }

    fun wanted(): Boolean = blocker() == null

    private val phone = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                // Ringing: keep listening for "Hey Jarvis, cut the call". Voice answer stops it while it asks.
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    ringing = true
                    @Suppress("DEPRECATION")
                    intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.takeIf { it.isNotBlank() }?.let { ringingNumber = it }
                }
                // In the call: Android silences other apps' microphones, so stop until it ends.
                TelephonyManager.EXTRA_STATE_OFFHOOK -> if (!inCall) {
                    ringing = false
                    ringingNumber = null
                    inCall = true
                    sync()
                }
                // The call ended: back to normal listening, with the earbud microphone set again.
                TelephonyManager.EXTRA_STATE_IDLE -> if (ringing || inCall) {
                    ringing = false
                    ringingNumber = null
                    inCall = false
                    stop("call ended")
                    main.postDelayed({ sync() }, 2_000)
                }
            }
        }
    }
    private var phoneRegistered = false

    /** Earbuds connect or go: listening follows them. */
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = sync()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
            if (running && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && earbudMic() == null) stop("earbuds disconnected")
        }
    }
    private var devicesRegistered = false

    /** Starts or stops to match the settings, Bike mode, the model, the microphone, the earbuds and calls. */
    fun sync() {
        if (destroyed) return
        if (VoiceMusic.switchedOn(c)) {
            if (!phoneRegistered) {
                val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) c.registerReceiver(phone, f, Context.RECEIVER_EXPORTED)
                else @Suppress("UnspecifiedRegisterReceiverFlag") c.registerReceiver(phone, f)
                phoneRegistered = true
            }
            if (!devicesRegistered) {
                am?.registerAudioDeviceCallback(devices, main)
                devicesRegistered = true
            }
        }
        val why = blocker()
        if (why == null && !running) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) start() }
        else if (why != null && running) stop(why)
        restoreSystemSounds()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun earbudMic(): AudioDeviceInfo? = am?.availableCommunicationDevices?.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    /** Logs why it does not listen, once per reason. */
    private fun notListening(why: String) {
        if (why != lastWhy) log("not listening: $why")
        lastWhy = why
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun start() {
        val dev = earbudMic() ?: return notListening("no earbuds connected for calls")
        if (!runCatching { am.setCommunicationDevice(dev) }.getOrDefault(false)) return notListening("earbud microphone refused")
        lastWhy = null
        running = true
        reported = 0
        val g = ++generation
        model?.let { return listen(it) }
        log("loading the speech model")
        // Loading takes a second or two: off the main thread, then listen on it.
        Thread {
            val m = VoskModel.load(c)
            // A model that does not load is broken (an unpacking cut short): it goes, so it can be downloaded again.
            if (m == null) VoskModel.delete(c)
            main.post {
                when {
                    destroyed || model != null -> m?.close()
                    m != null -> model = m
                }
                if (destroyed || !running || g != generation) return@post
                val cur = model ?: return@post stop("speech model broken: deleted, download it again")
                listen(cur)
            }
        }.apply { isDaemon = true; name = "vosk-load" }.start()
    }

    private fun listen(m: Model) {
        closeListener()
        val phrases = MusicCommands.grammar()
        VoskModel.missingWords(m, phrases).takeIf { it.isNotEmpty() }?.let { log("words the model does not know: $it") }
        val rec = runCatching { Recognizer(m, RATE.toFloat(), JSONArray(phrases).toString()) }
            .onFailure { log("recognizer not created: ${it.message}") }.getOrNull() ?: return stop("no recognizer")
        recognizer = rec
        val l = Listener(rec, generation)
        if (!l.begin()) return failed(generation, "microphone not opened")
        listener = l
        log("listening for \"Hey Jarvis\" (${VoiceMusic.label(c, VoiceMusic.app(c)!!)})")
    }

    /**
     * Records the microphone into Vosk on its own thread. Results reach the main thread with their start's number.
     * Was: Vosk's SpeechService: a failed read in its thread ended the app, and its last result came after a stop.
     */
    private inner class Listener(private val rec: Recognizer, private val gen: Int) : Thread("vosk-listen") {
        @Volatile private var stopping = false
        private var record: AudioRecord? = null

        @SuppressLint("MissingPermission")
        fun begin(): Boolean {
            val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), CHUNK * 2)
            val r = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            }.getOrNull() ?: return false
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
            runCatching { r.startRecording() }
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); return false }
            record = r
            isDaemon = true
            start()
            return true
        }

        override fun run() {
            val r = record ?: return
            val buf = ShortArray(CHUNK)
            while (!stopping) {
                val n = r.read(buf, 0, buf.size)
                if (n < 0) { main.post { failed(gen, "microphone read error $n") }; break }
                if (n == 0) continue
                val final = runCatching { rec.acceptWaveForm(buf, n) }
                    .getOrElse { e -> main.post { failed(gen, "recognizer: ${e.message}") }; break }
                if (final) {
                    val text = runCatching { rec.result }.getOrNull()
                    main.post { if (gen == generation && running) heard(text) }
                }
            }
            runCatching { r.stop() }
            r.release()
        }

        /** Stops the thread and waits for it. False: it did not end in time (then the recognizer stays open). */
        fun end(): Boolean {
            stopping = true
            runCatching { join(2_000) }
            return !isAlive
        }
    }

    private fun closeListener() {
        val l = listener
        listener = null
        val ended = l?.end() ?: true
        // Closing the recognizer while the thread still uses it would crash in native code.
        if (ended) runCatching { recognizer?.close() } else log("the listener did not stop in time")
        recognizer = null
    }

    /** A listening error: stop, then try again later, longer after each error in a row (up to a minute). */
    private fun failed(gen: Int, what: String) {
        if (gen != generation || !running) return
        failures++
        val wait = minOf(3_000L shl (failures - 1).coerceAtMost(5), 60_000L)
        log("$what: trying again in ${wait / 1000} s")
        stop("error")
        main.postDelayed({ sync() }, wait)
    }

    private fun heard(json: String?) {
        val text = runCatching { JSONObject(json ?: return).optString("text") }.getOrDefault("")
        if (text.isBlank() || text == "[unk]") return
        failures = 0
        val awaiting = System.currentTimeMillis() < awaitUntil
        when (val h = MusicCommands.interpret(text, awaiting)) {
            MusicCommands.Heard.Wake -> {
                awaitUntil = System.currentTimeMillis() + WINDOW_MS
                beep()
                log("heard \"hey jarvis\": waiting ${WINDOW_MS / 1000} s for the command")
            }
            is MusicCommands.Heard.Do -> {
                awaitUntil = 0L
                log("heard \"$text\" -> ${h.command}")
                // "Cut the call" rejects a ringing call; with none ringing there is nothing to cut.
                if (h.command == MusicCommands.Command.EndCall && !ringing) log("no ringing call to cut")
                else if (VoiceMusic.run(c, h.command) && h.command == MusicCommands.Command.EndCall)
                    com.spizganed.quickbuds.calls.AutoReply.declined(c, ringingNumber, "Hey Jarvis")
            }
            MusicCommands.Heard.Nothing -> if (++reported <= REPORT_MAX) log("heard \"$text\": no command")
        }
    }

    /** A short beep in the earbuds: "Hey Jarvis" was heard, say the command. */
    private fun beep() {
        releaseTone()
        val t = runCatching { android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80) }.getOrNull() ?: return
        tone = t
        t.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
        main.postDelayed({ releaseTone() }, 400)
    }

    private fun releaseTone() {
        runCatching { tone?.release() }
        tone = null
    }

    fun stop(why: String) {
        if (!running) return
        running = false
        generation++
        main.removeCallbacksAndMessages(null)
        closeListener()
        releaseTone()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { am?.clearCommunicationDevice() }
        awaitUntil = 0L
        if (why == "off") failures = 0
        log("stopped listening ($why)")
    }

    fun destroy() {
        stop("service ends")
        destroyed = true
        // Retries and restarts still waiting must not run without the service.
        main.removeCallbacksAndMessages(null)
        runCatching { model?.close() }
        model = null
        if (phoneRegistered) runCatching { c.unregisterReceiver(phone) }
        phoneRegistered = false
        if (devicesRegistered) runCatching { am?.unregisterAudioDeviceCallback(devices) }
        devicesRegistered = false
    }

    /**
     * The test build of 2026-10-19 muted system sounds against the old engine's tone. If it left them muted (the app
     * was replaced while listening), they come back once.
     */
    private fun restoreSystemSounds() {
        val p = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_TONE_MUTED, false)) return
        runCatching { am?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0) }
        p.edit().remove(KEY_TONE_MUTED).apply()
        log("system sounds back on")
    }

    private companion object {
        const val RATE = 16_000
        /** 0.2 s of audio per read, as Vosk's own recorder. */
        const val CHUNK = RATE / 5
        const val REPORT_MAX = 20
        /** After "Hey Jarvis" alone: this long to say the command. */
        const val WINDOW_MS = 6_000L
        const val KEY_TONE_MUTED = "voiceMusicToneMuted"
    }
}
