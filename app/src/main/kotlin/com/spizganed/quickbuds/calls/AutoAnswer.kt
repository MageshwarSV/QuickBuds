package com.spizganed.quickbuds.calls

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * Auto-answer: a ringing call is answered after the chosen delay, when earbuds are connected for calls, so it is
 * heard in them. Nothing happens when the call stops ringing first (the user answered or rejected it), when no
 * earbuds are connected for calls, or without the two phone permissions. Pure rules here; [AutoAnswerMonitor] runs them.
 */
object AutoAnswer {
    const val KEY_ENABLED = "autoAnswer"
    const val KEY_DELAY = "autoAnswerDelay"
    val DELAYS = listOf(3, 5, 10, 15)
    const val DEFAULT_DELAY = 5

    val PERMISSIONS = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false) && permitted(c) && BudsService.backgroundAllowed(c)

    fun delaySeconds(c: Context) = prefs(c).getInt(KEY_DELAY, DEFAULT_DELAY).takeIf { it in DELAYS } ?: DEFAULT_DELAY

    /**
     * A second call (it rings during a call): auto-answer answers it too, and Android puts the current call on
     * hold. Off by default. Was: it was always answered, as a first call.
     */
    const val KEY_SECOND_CALL = "autoAnswerSecondCall"

    fun secondCall(c: Context) = prefs(c).getBoolean(KEY_SECOND_CALL, false)

    fun setSecondCall(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_SECOND_CALL, on).apply()

    /**
     * A repeated call: the same number again within [REPEAT_WINDOW_MS] gets through the contact filter (someone
     * who tries twice may need you). Off by default.
     */
    const val KEY_REPEAT = "autoAnswerRepeat"
    const val REPEAT_WINDOW_MS = 3 * 60_000L

    fun repeatOn(c: Context) = prefs(c).getBoolean(KEY_REPEAT, false)

    fun setRepeat(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_REPEAT, on).apply()

    /** Whether [number] rang before within [REPEAT_WINDOW_MS]: [recent] holds earlier calls (number, start). Pure. */
    fun repeatCall(number: String?, nowMs: Long, recent: List<Pair<String, Long>>): Boolean =
        number != null && recent.any { (n, at) -> nowMs - at in 0..REPEAT_WINDOW_MS && sameNumber(n, number) }

    /** What a call that rings gets. Pure. [inCall]: another call is already on. Voice answer never asks then. */
    enum class Ring { FIRST, SECOND_ANSWER, SECOND_IGNORE }

    fun ring(inCall: Boolean, secondCall: Boolean): Ring = when {
        !inCall -> Ring.FIRST
        secondCall -> Ring.SECOND_ANSWER
        else -> Ring.SECOND_IGNORE
    }

    fun permitted(c: Context) = PERMISSIONS.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** At the end of the delay: answer only if the call still rings and earbuds are connected for calls. */
    fun shouldAnswer(stillRinging: Boolean, earbudsConnected: Boolean): Boolean = stillRinging && earbudsConnected

    // --- Which calls: all, only chosen contacts, or all except chosen contacts ---

    enum class Filter { ALL, ONLY, EXCEPT }

    /** A chosen contact: the name to show and the number to match. */
    data class Contact(val name: String, val number: String)

    const val KEY_FILTER = "autoAnswerFilter"
    private const val KEY_CONTACTS = "autoAnswerContacts"

    /** Android gives the caller's number only with this permission (Android 9+). */
    const val CALL_LOG = Manifest.permission.READ_CALL_LOG

    fun filter(c: Context): Filter =
        runCatching { Filter.valueOf(prefs(c).getString(KEY_FILTER, Filter.ALL.name)!!) }.getOrDefault(Filter.ALL)

    fun setFilter(c: Context, f: Filter) = prefs(c).edit().putString(KEY_FILTER, f.name).apply()

    fun callLogPermitted(c: Context) = c.checkSelfPermission(CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun contacts(c: Context): List<Contact> = runCatching {
        val arr = org.json.JSONArray(prefs(c).getString(KEY_CONTACTS, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Contact(o.getString("name"), o.getString("number")) } }
    }.getOrDefault(emptyList())

    fun setContacts(c: Context, list: List<Contact>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("name", it.name).put("number", it.number)) }
        prefs(c).edit().putString(KEY_CONTACTS, arr.toString()).apply()
    }

    /** The digits only; "+91 98765-43210" and "098765 43210" both end in 9876543210. */
    private fun digits(n: String) = n.filter { it.isDigit() }

    /** Same number: the last 10 digits, so a country code or a trunk 0 does not matter; shorter numbers must equal. */
    fun sameNumber(a: String, b: String): Boolean {
        val x = digits(a); val y = digits(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return if (x.length >= 10 && y.length >= 10) x.takeLast(10) == y.takeLast(10) else x == y
    }

    /**
     * Whether the caller passes the filter. A hidden or unknown number ([number] null) is not a chosen contact:
     * ONLY skips it, EXCEPT answers it.
     */
    fun callerAllowed(filter: Filter, number: String?, contacts: List<Contact>): Boolean {
        val chosen = number != null && contacts.any { sameNumber(it.number, number) }
        return when (filter) {
            Filter.ALL -> true
            Filter.ONLY -> chosen
            Filter.EXCEPT -> !chosen
        }
    }
}

/**
 * In [BudsService] while it runs: the phone state broadcast says when a call rings; after the delay, if the call still
 * rings and earbuds are connected for calls, it is answered.
 * "Connected for calls" is the headset profile (or LE Audio), not media audio or QuickBuds' own link: a call goes to
 * earbuds only over that profile. Without it the call would open on the phone's earpiece, with nobody holding it.
 */
class AutoAnswerMonitor(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    private val answer = Runnable { tryAnswer() }

    /** The ringing call's number, when Android gives it (call log permission): one of the two broadcasts has it. */
    private var incoming: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    // The probe measures this call: its second broadcast must not auto-answer it either.
                    if (probing) return
                    // Voice answer probe (Dev tools): measures this one call, answers nothing.
                    if (VoiceProbe.armed(context) && !VoiceAnswer.enabled(context)) {
                        VoiceProbe.setArmed(context, false)
                        probing = true
                        handler.removeCallbacks(answer)
                        VoiceProbe.run(context, "ringing")
                        onProbeDone()
                        return
                    }
                    // A second call rings during a call: answered only when the setting says so, and never with a
                    // question in the earbuds (Android gives the microphone to the call).
                    val ring = AutoAnswer.ring(inCall, AutoAnswer.secondCall(context))
                    if (ring == AutoAnswer.Ring.SECOND_IGNORE) {
                        if (ringingAt == 0L) PacketLogger.log("CALL: second call during a call: not answered (setting off)")
                        ringingAt = SystemClock.elapsedRealtime()
                        return
                    }
                    if (ringingAt == 0L) ringingAt = SystemClock.elapsedRealtime()
                    @Suppress("DEPRECATION")
                    intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.takeIf { it.isNotBlank() }?.let { incoming = it }
                    // Once per call, when the number is known: a repeated call gets through the contact filter.
                    incoming?.takeIf { !numberSeen }?.let { n ->
                        numberSeen = true
                        val now = SystemClock.elapsedRealtime()
                        repeat = AutoAnswer.repeatOn(context) && AutoAnswer.repeatCall(n, now, recent)
                        if (repeat) PacketLogger.log("CALL: the same number again within 3 min: the contact filter is skipped")
                        recent.removeAll { now - it.second > AutoAnswer.REPEAT_WINDOW_MS }
                        recent += n to now
                    }
                    // Voice answer, once the number is known (the second of the two broadcasts): it asks the
                    // user, so the timed auto-answer waits. No clear reply: auto-answer answers then.
                    val number = incoming
                    if (number != null && !voiceAsked && ring == AutoAnswer.Ring.FIRST && VoiceAnswer.enabled(context)) {
                        voiceAsked = true
                        val call = callCount
                        callHeadset { headset ->
                            // The call ended while the earbuds were checked: nothing to ask.
                            if (call != callCount) return@callHeadset
                            // The timer waits while voice answer asks; no clear reply, it answers on time.
                            handler.removeCallbacks(answer)
                            voiceTookCall = true
                            if (!VoiceAnswer.onRinging(context, number, headset, ignoreFilter = repeat) { answerOnTime() }) {
                                voiceTookCall = false
                                if (AutoAnswer.enabled(context)) answerOnTime()
                            }
                        }
                    }
                    if (!AutoAnswer.enabled(context) || voiceTookCall) return
                    // The broadcast comes twice (with and without the number): one timer, from the first ring.
                    answerOnTime()
                    PacketLogger.log("CALL: ${if (ring == AutoAnswer.Ring.FIRST) "ringing" else "second call during a call"}, " +
                        "auto-answer in ${AutoAnswer.delaySeconds(context)} s if the earbuds are connected")
                }
                // Answered or rejected by the user, or ended: nothing to do. A call is on until the phone is idle:
                // after a second call, the first one can still be on.
                TelephonyManager.EXTRA_STATE_OFFHOOK -> { endCall(); inCall = true }
                TelephonyManager.EXTRA_STATE_IDLE -> { endCall(); inCall = false }
            }
        }
    }

    /** The voice probe ran: the service drops the microphone type again. */
    var onProbeDone: () -> Unit = {}

    /** This call: voice answer looked at it once; it asked the user (the timed auto-answer then waits). */
    private var voiceAsked = false
    private var voiceTookCall = false
    /** This call is the voice probe's: no auto-answer for it. */
    private var probing = false
    /** When this call started to ring (elapsed time), or 0: the delay counts from then. */
    private var ringingAt = 0L
    /** Counts calls that stopped ringing: an earbuds check that answers after its call ended is ignored. */
    private var callCount = 0
    /** A call is on (answered, dialled or on hold): a call that rings now is a second call. */
    private var inCall = false
    /** This call's number was looked at; it is a repeated call ([AutoAnswer.repeatCall]). */
    private var numberSeen = false
    private var repeat = false
    /** Calls of the last minutes: number and when it rang (elapsed time). Only in memory. */
    private val recent = mutableListOf<Pair<String, Long>>()

    /** Answers when the delay since the first ring is over: at once if it is already over. */
    private fun answerOnTime() {
        val start = if (ringingAt == 0L) SystemClock.elapsedRealtime() else ringingAt
        val left = start + AutoAnswer.delaySeconds(context) * 1000L - SystemClock.elapsedRealtime()
        handler.removeCallbacks(answer)
        handler.postDelayed(answer, left.coerceAtLeast(0L))
    }

    /** The call was answered, rejected or ended (or the monitor stops): everything for it ends. */
    private fun endCall() {
        handler.removeCallbacks(answer)
        VoiceAnswer.onStoppedRinging()
        incoming = null
        voiceAsked = false
        voiceTookCall = false
        probing = false
        ringingAt = 0L
        numberSeen = false
        repeat = false
        callCount++
    }

    /** Registered while the feature is on and allowed, or the voice probe is armed; on start and on setting changes. */
    fun update() {
        val want = AutoAnswer.enabled(context) || VoiceProbe.armed(context) || VoiceAnswer.enabled(context)
        if (want && !registered) {
            // Registered during a call: no broadcast tells it, so ask once.
            @Suppress("DEPRECATION")
            inCall = runCatching { context.getSystemService(TelephonyManager::class.java)?.callState == TelephonyManager.CALL_STATE_OFFHOOK }
                .getOrDefault(false)
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            // A system broadcast: exported, so the system's sender reaches it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
            registered = true
        } else if (!want && registered) {
            stop()
        }
    }

    fun stop() {
        endCall()
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    @SuppressLint("MissingPermission")
    private fun tryAnswer() {
        if (!AutoAnswer.enabled(context)) return
        val ringing = runCatching {
            context.getSystemService(TelephonyManager::class.java)?.callState == TelephonyManager.CALL_STATE_RINGING
        }.getOrDefault(false)
        if (!ringing) return
        val filter = AutoAnswer.filter(context)
        if (!repeat && !AutoAnswer.callerAllowed(filter, incoming, AutoAnswer.contacts(context))) {
            // No number in the log: it is personal data.
            PacketLogger.log("CALL: not answered: the caller does not pass the contact filter ($filter, " +
                "${if (incoming == null) "number not known" else "number known"})")
            return
        }
        callHeadset { name ->
            if (!AutoAnswer.shouldAnswer(ringing, name != null)) {
                PacketLogger.log("CALL: not answered: no earbuds connected for calls")
                return@callHeadset
            }
            runCatching {
                @Suppress("DEPRECATION")
                context.getSystemService(TelecomManager::class.java)?.acceptRingingCall()
                PacketLogger.log("CALL: auto-answered after ${AutoAnswer.delaySeconds(context)} s on $name")
            }.onFailure { PacketLogger.error("auto-answer", it) }
        }
    }

    /**
     * The name of a device connected for calls (headset profile, or LE Audio where the phone has it), or null.
     * On the main thread; null when Bluetooth is off, cannot be asked, or does not answer in time.
     */
    @SuppressLint("MissingPermission")
    private fun callHeadset(result: (String?) -> Unit) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return result(null)
        // A profile the phone has switched off accepts the request and never answers: LE Audio only where it is on.
        val leAudio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { adapter.isLeAudioSupported == BluetoothStatusCodes.FEATURE_SUPPORTED }.getOrDefault(false)
        val profiles = if (leAudio) listOf(BluetoothProfile.HEADSET, BluetoothProfile.LE_AUDIO) else listOf(BluetoothProfile.HEADSET)
        var left = profiles.size
        var found: String? = null
        var delivered = false
        fun deliver() {
            if (delivered) return
            delivered = true
            result(found)
        }
        handler.postDelayed({ deliver() }, HEADSET_TIMEOUT_MS)
        for (p in profiles) {
            val asked = runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        runCatching { proxy.connectedDevices.firstOrNull() }.getOrNull()?.let { d ->
                            if (found == null) found = runCatching { d.name }.getOrNull() ?: d.address
                        }
                        runCatching { adapter.closeProfileProxy(profile, proxy) }
                        handler.post { if (--left == 0) deliver() }
                    }
                    override fun onServiceDisconnected(profile: Int) {}
                }, p)
            }.getOrDefault(false)
            if (!asked && --left == 0) handler.post { deliver() }
        }
    }

    private companion object {
        const val HEADSET_TIMEOUT_MS = 2_000L
    }
}
