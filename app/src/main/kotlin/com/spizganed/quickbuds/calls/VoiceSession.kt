package com.spizganed.quickbuds.calls

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.RequiresApi
import com.spizganed.quickbuds.bluetooth.PacketLogger
import java.util.Locale

/**
 * One spoken question and the reply, through the earbuds: open the earbuds' microphone (the call link), speak
 * [run]'s prompt over that link, then listen with the phone's on-device recognizer and hand back YES, NO or UNCLEAR
 * ([VoicePhrases]). Never online. Measured first with [VoiceProbe] on the developer's phone (2026-10-18): earbud
 * microphone during a ringing call, app in the background, ringer on.
 *
 * The link opens and the recognizer's language is found while the prompt plays, so listening starts as soon as the
 * prompt ends. Was: both after the prompt, about 2 to 3 s in which a quick "yes" was lost. With [listenMs], an
 * unclear reply or silence listens again (the recognizer's start tone each time) until a YES or NO, or that long.
 *
 * Main thread only. [cancel] stops everything at once without a result (the call stopped ringing).
 */
class VoiceSession(context: Context) {

    private val c = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val am = c.getSystemService(AudioManager::class.java)
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var micOpen = false
    private var done = false
    private var onResult: ((VoicePhrases.Answer, String) -> Unit)? = null

    /** The prompt ended (or timed out), and the language is known: listening may start. */
    private var spoken = false
    private var langKnown = false
    private var lang: String? = null
    /** Listening ends at this time (elapsed), set when the first listen starts; 0 before. */
    private var deadline = 0L
    private var listenMs = 0L
    /** Each listen counts up: a time box from an earlier listen does nothing. */
    private var attempt = 0
    private var lastHeard = "nothing"

    private fun log(msg: String) = PacketLogger.log("VOICE ANSWER: $msg")

    /** A Bluetooth output to speak through. Never the phone's speaker: without one, nothing is said. */
    fun bluetoothOutput(): Boolean = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().any {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    /**
     * Asks [prompt] and listens. [listenMs]: how long to keep listening after unclear replies; 0 listens once
     * (Settings › Try it now). [result] gets the answer and what was heard, at once when it cannot ask at all.
     */
    fun run(prompt: String, promptLocale: Locale, listenMs: Long = 0L, result: (VoicePhrases.Answer, String) -> Unit) {
        onResult = result
        this.listenMs = listenMs
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !SpeechRecognizer.isOnDeviceRecognitionAvailable(c)) {
            return finish(VoicePhrases.Answer.UNCLEAR, "no on-device recognizer")
        }
        if (!bluetoothOutput()) return finish(VoicePhrases.Answer.UNCLEAR, "no Bluetooth output: silent")
        if (!openMic()) return finish(VoicePhrases.Answer.UNCLEAR, "earbud microphone not available")
        // While the prompt plays: the language for the recognizer.
        OnDeviceEnglish.resolve(c) { l -> lang = l; langKnown = true; maybeListen() }
        tts = TextToSpeech(c) { status ->
            val t = tts
            if (done || t == null) return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) { log("speech engine failed ($status)"); return@TextToSpeech spokenNow() }
            val l = t.setLanguage(promptLocale)
            if (l == TextToSpeech.LANG_MISSING_DATA || l == TextToSpeech.LANG_NOT_SUPPORTED) t.language = Locale("en", "IN")
            // On the call link that is already open: no switch from music audio after the prompt.
            t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { main.post { spokenNow() } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { main.post { spokenNow() } }
            })
            t.speak(prompt, TextToSpeech.QUEUE_FLUSH, null, "voice-answer")
        }
        // A prompt that never ends must not hold the listen back.
        main.postDelayed({ spokenNow() }, SPEAK_TIMEOUT_MS)
    }

    /** The earbuds' microphone, on the call link (SCO or LE Audio). */
    @SuppressLint("MissingPermission")
    private fun openMic(): Boolean {
        micOpen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dev = am?.availableCommunicationDevices?.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            dev != null && runCatching { am!!.setCommunicationDevice(dev) }.getOrDefault(false)
        } else @Suppress("DEPRECATION") runCatching { am?.startBluetoothSco(); true }.getOrDefault(false)
        return micOpen
    }

    private fun spokenNow() {
        if (spoken) return
        spoken = true
        maybeListen()
    }

    private fun maybeListen() {
        if (done || !spoken || !langKnown || deadline != 0L) return
        val l = lang ?: return finish(VoicePhrases.Answer.UNCLEAR, "no English on-device")
        deadline = SystemClock.elapsedRealtime() + maxOf(listenMs, LISTEN_MS + 3_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) recognize(l)
    }

    /** Listens once more after an unclear reply, if there is time left; else ends UNCLEAR. */
    private fun again(why: String) {
        if (done) return
        lastHeard = why
        val l = lang
        if (listenMs <= 0L || l == null || SystemClock.elapsedRealtime() + RETRY_PAUSE_MS + 1_000L >= deadline) {
            return finish(VoicePhrases.Answer.UNCLEAR, why)
        }
        log("$why: listening again")
        attempt++
        runCatching { recognizer?.destroy() }
        recognizer = null
        // Back to back, the on-device service drops the next recognizer: a short pause.
        val a = attempt
        main.postDelayed({ if (a == attempt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) recognize(l) }, RETRY_PAUSE_MS)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun recognize(lang: String) {
        if (done) return
        val a = ++attempt
        val rec = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(c) }.getOrNull()
            ?: return finish(VoicePhrases.Answer.UNCLEAR, "recognizer not created")
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (a != attempt) return
                // Nothing said, nothing matched, or the engine busy: listen again. Anything else ends it.
                if (error in RETRY_ERRORS) again("recognizer error $error") else finish(VoicePhrases.Answer.UNCLEAR, "recognizer error $error")
            }
            override fun onResults(results: Bundle?) {
                if (a != attempt) return
                val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val answer = VoicePhrases.classifyAll(said)
                if (answer == VoicePhrases.Answer.UNCLEAR) again("heard ${said.take(3)}") else finish(answer, "heard ${said.take(3)}")
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching {
            rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5))
        }.onFailure { finish(VoicePhrases.Answer.UNCLEAR, "start failed") }
        // The time box of this listen: nothing said within it is unclear, and maybe the next listen.
        main.postDelayed({ if (a == attempt) runCatching { recognizer?.stopListening() } }, LISTEN_MS)
        main.postDelayed({ if (a == attempt) again("nothing heard") }, LISTEN_MS + 3_000L)
        // The whole listening time.
        main.postDelayed({ if (a == attempt) finish(VoicePhrases.Answer.UNCLEAR, "no clear reply ($lastHeard)") },
            (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    /** Releases the microphone and the engines first, then reports: an answered call gets the audio route back. */
    private fun finish(answer: VoicePhrases.Answer, detail: String) {
        if (done) return
        release()
        log("$detail -> $answer")
        onResult?.invoke(answer, detail)
        onResult = null
    }

    /** Stop now, no result: the call was answered by hand, rejected or ended. */
    fun cancel() {
        if (done) return
        release()
        onResult = null
        log("cancelled: the call stopped ringing")
    }

    private fun release() {
        done = true
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        if (micOpen) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { am?.clearCommunicationDevice() }
            else @Suppress("DEPRECATION") runCatching { am?.stopBluetoothSco() }
            micOpen = false
        }
    }

    companion object {
        /** One listen: this long, then the recognizer gives what it heard. */
        const val LISTEN_MS = 6_000L
        private const val SPEAK_TIMEOUT_MS = 8_000L
        private const val RETRY_PAUSE_MS = 600L
        /** Speech timeout, no match, engine busy, client, server disconnected: worth one more listen. */
        private val RETRY_ERRORS = setOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT, 11)
    }
}

/**
 * The English the phone's on-device recognizer has installed (en-IN first, else any English), asked once per
 * process and remembered. Voice answer's; voice music uses Vosk ([com.spizganed.quickbuds.music.VoskModel]). Main thread.
 */
object OnDeviceEnglish {
    private var cached: String? = null

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun resolve(context: Context, then: (String?) -> Unit) {
        cached?.let { return then(it) }
        val c = context.applicationContext
        val check = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(c) }.getOrNull() ?: return then(null)
        check.checkRecognitionSupport(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), c.mainExecutor,
            object : android.speech.RecognitionSupportCallback {
                override fun onSupportResult(s: android.speech.RecognitionSupport) {
                    check.destroy()
                    val l = s.installedOnDeviceLanguages
                    cached = l.firstOrNull { it.equals("en-IN", true) } ?: l.firstOrNull { it.startsWith("en") }
                    // Back to back, the on-device service drops the second recognizer: a pause.
                    Handler(Looper.getMainLooper()).postDelayed({ then(cached) }, 600)
                }
                override fun onError(error: Int) { check.destroy(); then(null) }
            })
    }
}
