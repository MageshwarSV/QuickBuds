package com.spizganed.quickbuds.findphone

import android.content.Context
import android.net.Uri
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * Find My Phone: 4 taps on one earbud ring this phone ([FinderAlarm]). The taps come from the supported buds'
 * gesture event (`F1`, PROTOCOL.md §6), which QuickBuds subscribes to only while this is on. Other brands'
 * earbuds keep their taps to themselves: no detection there. Off by default.
 */
object FindPhone {
    const val KEY_ENABLED = "findPhone"
    const val KEY_SOUND = "findPhoneSound"
    const val KEY_CUSTOM = "findPhoneCustomUri"

    /** The alarm sound: QuickBuds' finder sound (or a file the user picked), or the phone's ringtone. */
    enum class Sound { FINDER, RINGTONE }

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()

    fun sound(c: Context): Sound =
        runCatching { Sound.valueOf(prefs(c).getString(KEY_SOUND, Sound.FINDER.name)!!) }.getOrDefault(Sound.FINDER)
    fun setSound(c: Context, s: Sound) = prefs(c).edit().putString(KEY_SOUND, s.name).apply()

    /** A file the user picked for the finder sound, or null: QuickBuds' own. */
    fun customUri(c: Context): Uri? = prefs(c).getString(KEY_CUSTOM, null)?.let { Uri.parse(it) }
    fun setCustomUri(c: Context, uri: Uri?) = prefs(c).edit().apply {
        if (uri == null) remove(KEY_CUSTOM) else putString(KEY_CUSTOM, uri.toString())
    }.apply()

    /**
     * Whether the connected supported buds offer the gesture event: true, false, or null (no supported buds
     * connected, or not known yet). Set by the connection when the buds list their events (`0x8200`).
     */
    @Volatile var gestures: Boolean? = null

    /** The taps of the connected buds; reset on each connect. Main thread. */
    val counter = TapCounter()

    /**
     * Counts taps on one earbud. Pure. Each gesture event says how many taps it was (single 1, double 2, triple 3),
     * so 4 taps count whatever the buds made of them: 4 singles, two doubles, a triple and a single. Holds and
     * slides do not count. The same event again within [DUPLICATE_MS] is one event (the buds can report a double
     * tap twice). A tap on the other earbud, or a pause longer than [WINDOW_MS] since the first tap, starts again.
     */
    class TapCounter {
        private var side = -1
        private var taps = 0
        private var firstMs = 0L
        private var lastAct = -1
        private var lastMs = 0L

        /** One gesture event; true when it completes [TAPS] taps (the count then starts again). */
        fun feed(side: Int, act: Int, nowMs: Long): Boolean {
            val n = when (act) { 1 -> 1; 2 -> 2; 3 -> 3; else -> 0 }
            if (n == 0) { reset(); return false }
            if (side == this.side && act == lastAct && nowMs - lastMs < DUPLICATE_MS) return false
            if (side != this.side || nowMs - firstMs > WINDOW_MS) {
                this.side = side
                taps = 0
                firstMs = nowMs
            }
            taps += n
            lastAct = act
            lastMs = nowMs
            if (taps >= TAPS) { reset(); return true }
            return false
        }

        fun reset() {
            side = -1; taps = 0; firstMs = 0L; lastAct = -1; lastMs = 0L
        }
    }

    const val TAPS = 4
    const val WINDOW_MS = 2_500L
    const val DUPLICATE_MS = 300L
}
