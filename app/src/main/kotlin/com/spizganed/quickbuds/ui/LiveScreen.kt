package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.spizganed.quickbuds.widget.WidgetStateStore
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Keeps an open screen up to date: [repaint] runs when a setting changes (here or from outside: the Bike mode tile,
 * the background service), when the buds report (a level, a bud into the case), or on [changed]. One repaint for a
 * burst of changes, after [settleMs]. Was: these screens showed what was true when they opened. Call [resume] and
 * [pause] from the screen's own. Main thread.
 */
class LiveScreen(private val activity: Activity, private val settleMs: Long = 250L, private val repaint: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val soon = Runnable { if (!activity.isFinishing && !activity.isDestroyed) repaint() }
    private fun later() {
        main.removeCallbacks(soon)
        main.postDelayed(soon, settleMs)
    }

    // Held here: SharedPreferences keeps its listeners only weakly.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> later() }
    private val budsListener: (WidgetStateStore.State) -> Unit = { later() }
    private val eventListener: () -> Unit = { later() }

    private fun prefs() = activity.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    fun resume() {
        prefs().registerOnSharedPreferenceChangeListener(prefsListener)
        WidgetStateStore.addListener(budsListener)
        listeners.add(eventListener)
    }

    fun pause() {
        prefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
        WidgetStateStore.removeListener(budsListener)
        listeners.remove(eventListener)
        main.removeCallbacks(soon)
    }

    companion object {
        private val listeners = CopyOnWriteArrayList<() -> Unit>()

        /** Something that is no setting changed (the service took the microphone): open screens repaint. */
        fun changed() {
            Handler(Looper.getMainLooper()).post { listeners.forEach { it() } }
        }
    }
}
