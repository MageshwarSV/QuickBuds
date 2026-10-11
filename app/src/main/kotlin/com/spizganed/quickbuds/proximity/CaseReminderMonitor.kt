package com.spizganed.quickbuds.proximity

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * Runs [CaseReminder] in [com.spizganed.quickbuds.bluetooth.BudsService]: wear reports in, the delayed
 * check, the notification. While a reminder is pending, a partial wake lock holds the CPU for the
 * [CaseReminder.DELAY_MS] wait only, so the reminder is not late when the phone sleeps.
 */
class CaseReminderMonitor(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val reminder = CaseReminder()
    private var wakeLock: PowerManager.WakeLock? = null
    private val check = Runnable { apply(reminder.check(SystemClock.elapsedRealtime())); schedule() }

    fun onWear(left: Int, right: Int) {
        if (!enabled(context)) return
        apply(reminder.onWear(left, right, SystemClock.elapsedRealtime()))
        schedule()
    }

    fun onDisconnected() {
        reminder.onDisconnected()
        schedule()
    }

    /** Settings › Case reminder changed. */
    fun onSettingsChanged() {
        if (!enabled(context)) { apply(reminder.stop()); schedule() }
    }

    fun destroy() {
        handler.removeCallbacks(check)
        release()
    }

    private fun schedule() {
        handler.removeCallbacks(check)
        val due = reminder.dueAt ?: return release()
        val wait = (due - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        handler.postDelayed(check, wait)
        val wl = wakeLock ?: context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuickBuds:caseReminder")
            ?.apply { setReferenceCounted(false) }
            ?.also { wakeLock = it }
        runCatching { wl?.acquire(wait + 2_000) }
    }

    private fun release() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
    }

    private fun apply(effect: CaseReminder.Effect) {
        when (effect) {
            is CaseReminder.Effect.Notify -> {
                PacketLogger.log("CASE REMINDER: ${effect.outside} bud still out of the case")
                LossNotifier.caseReminder(context, effect.outside)
            }
            CaseReminder.Effect.Clear -> LossNotifier.clearCaseReminder(context)
            CaseReminder.Effect.None -> {}
        }
    }

    companion object {
        /** Settings › Case reminder (default on: it reads nothing from the radio, only the buds' wear reports). */
        const val KEY_ENABLED = "caseReminder"

        fun enabled(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
    }
}
