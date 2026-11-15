package com.spizganed.quickbuds.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.ui.ThemeRes
import java.util.Locale

/**
 * Voice answer: a ringing call from a known contact is announced in the earbuds in Tamil ("Ashraf அழைக்கிறார்.
 * எடுக்கட்டுமா?"), then a spoken reply decides: YES answers, NO declines. An unclear reply listens again; with
 * auto-answer on, [LISTEN_WITH_AUTO_MS] without a clear reply goes to auto-answer. Else the phone rings on.
 *
 * Silent, and the phone rings as usual, when: the feature is off; a permission is missing; the number is hidden
 * or not in the contacts (no prompt for strangers); the caller does not pass auto-answer's contact filter (one
 * filter for every call feature); no earbuds are
 * connected for calls (never the phone's speaker); or the microphone is not ready (QuickBuds not opened since the
 * background service started). Settings stay on the phone (the app's preferences).
 */
object VoiceAnswer {

    const val KEY_ENABLED = "voiceAnswer"

    /** What it needs: see a call ring and its number, read the contact's name, listen, answer and decline. */
    val PERMISSIONS = arrayOf(
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS,
        Manifest.permission.RECORD_AUDIO, Manifest.permission.ANSWER_PHONE_CALLS
    )

    /** The prompt, Tamil by the developer's choice: "<name> is calling. Shall I take it?". */
    fun prompt(name: String) = "$name அழைக்கிறார். எடுக்கட்டுமா?"

    /** With auto-answer on, by the developer's choice: no clear reply in this long, auto-answer answers. */
    const val LISTEN_WITH_AUTO_MS = 20_000L
    /** Without auto-answer: listening ends when the call stops ringing, at the latest after this long. */
    const val LISTEN_MS = 60_000L
    val PROMPT_LOCALE: Locale = Locale("ta", "IN")

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    fun permitted(c: Context) = PERMISSIONS.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun switchedOn(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false)

    fun enabled(c: Context) = switchedOn(c) && permitted(c) && BudsService.backgroundAllowed(c)

    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()

    /**
     * Whether a caller gets the prompt. Pure. [contactName]: the name in the phone's contacts, null when the number
     * is not there (a stranger: never prompted, whatever the filter). [filter] and [chosen]: auto-answer's.
     */
    fun promptFor(number: String?, contactName: String?, filter: AutoAnswer.Filter, chosen: List<AutoAnswer.Contact>): Boolean =
        number != null && contactName != null && AutoAnswer.callerAllowed(filter, number, chosen)

    /** What a reply does. Pure. Only while the call still rings; UNCLEAR is no decision (auto-answer may act). */
    enum class Action { ANSWER, DECLINE, NONE }

    fun action(answer: VoicePhrases.Answer, stillRinging: Boolean): Action = when {
        !stillRinging -> Action.NONE
        answer == VoicePhrases.Answer.YES -> Action.ANSWER
        answer == VoicePhrases.Answer.NO -> Action.DECLINE
        else -> Action.NONE
    }

    /** The contact's display name for [number], or null (not a contact, or no permission). */
    fun contactName(c: Context, number: String): String? = runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        c.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    // --- One call ---

    /** The question about a ringing call. */
    private var session: VoiceSession? = null
    /** Try it now: apart from [session], so a call's end does not cancel it. */
    private var trySession: VoiceSession? = null

    /** Asking now (a call or Try it now): voice music's "Hey Jarvis" stays out of the way. */
    val asking: Boolean get() = session != null || trySession != null

    /** Called on the main thread when [asking] changes: voice music stops or listens again. */
    var askingChanged: (() -> Unit)? = null

    /**
     * A call rings with a known [number]. [headsetName]: a device connected for calls, or null. True when a prompt
     * started (the caller qualifies): the timed auto-answer then waits for the reply. [undecided] runs when the
     * reply was no YES or NO and the call still rings: auto-answer's turn.
     */
    fun onRinging(context: Context, number: String, headsetName: String?, undecided: () -> Unit): Boolean {
        val c = context.applicationContext
        if (!enabled(c) || session != null) return false
        val name = contactName(c, number)
        if (!promptFor(number, name, AutoAnswer.filter(c), AutoAnswer.contacts(c))) {
            PacketLogger.log("VOICE ANSWER: no prompt (${if (name == null) "not a contact" else "the contact filter"})")
            return false
        }
        if (headsetName == null) { PacketLogger.log("VOICE ANSWER: no prompt (no earbuds connected for calls)"); return false }
        if (!BudsService.microphoneReady) {
            PacketLogger.log("VOICE ANSWER: no prompt (microphone not ready: open QuickBuds once)")
            return false
        }
        val s = VoiceSession(c)
        session = s
        // Voice music lets go of the earbud microphone first, so it does not clear the one this session sets.
        askingChanged?.invoke()
        PacketLogger.log("VOICE ANSWER: asking about a contact's call on $headsetName")
        var started = false
        var early: String? = null
        // Unclear replies listen again: with auto-answer on, until it may answer; else while the call rings.
        val listen = if (AutoAnswer.enabled(c)) LISTEN_WITH_AUTO_MS else LISTEN_MS
        s.run(prompt(name!!), PROMPT_LOCALE, listen) { answer, detail -> if (started) act(c, answer, undecided) else early = detail }
        started = true
        // It could not ask at all (no on-device recognizer, no Bluetooth output): as if voice answer were off, so
        // auto-answer keeps its own delay. Was: the call was answered at once.
        early?.let {
            session = null
            askingChanged?.invoke()
            PacketLogger.log("VOICE ANSWER: no prompt ($it)")
            return false
        }
        return true
    }

    /** The call stopped ringing (answered by hand, rejected, ended): stop listening. */
    fun onStoppedRinging() {
        val s = session ?: return
        s.cancel()
        session = null
        askingChanged?.invoke()
    }

    @SuppressLint("MissingPermission")
    private fun act(c: Context, answer: VoicePhrases.Answer, undecided: () -> Unit) {
        session = null
        val ringing = runCatching {
            c.getSystemService(TelephonyManager::class.java)?.callState == TelephonyManager.CALL_STATE_RINGING
        }.getOrDefault(false)
        val tm = c.getSystemService(TelecomManager::class.java)
        when (action(answer, ringing)) {
            Action.ANSWER -> runCatching {
                @Suppress("DEPRECATION") tm?.acceptRingingCall()
                PacketLogger.log("VOICE ANSWER: answered")
            }.onFailure { PacketLogger.error("voice answer accept", it) }
            Action.DECLINE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching {
                @Suppress("DEPRECATION") val ended = tm?.endCall() == true
                PacketLogger.log("VOICE ANSWER: decline ${if (ended) "done" else "refused by Android"}")
            }.onFailure { PacketLogger.error("voice answer decline", it) }
            else PacketLogger.log("VOICE ANSWER: decline needs Android 9")
            Action.NONE -> if (ringing) { PacketLogger.log("VOICE ANSWER: no clear reply"); undecided() }
        }
        askingChanged?.invoke()
    }

    /** Settings › Try it now: the prompt and one listen, no call involved. [result] gets the answer and what was heard. */
    fun tryNow(context: Context, result: (VoicePhrases.Answer, String) -> Unit) {
        if (asking) return
        val s = VoiceSession(context)
        trySession = s
        askingChanged?.invoke()
        s.run(prompt("சோதனை"), PROMPT_LOCALE) { answer, detail ->
            trySession = null
            askingChanged?.invoke()
            result(answer, detail)
        }
    }
}
