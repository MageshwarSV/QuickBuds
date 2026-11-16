package com.spizganed.quickbuds.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * Auto-reply: a call declined by voice (voice answer's "no", or "Hey Jarvis, cut the call") gets a text message,
 * by default "I'm riding. I'll call you back." Off by default; needs the SMS permission. The phone's default SIM
 * sends it. No number in the log: it is personal data.
 */
object AutoReply {
    const val KEY_ENABLED = "autoReply"
    const val KEY_TEXT = "autoReplyText"
    /** The longest text: one SMS. */
    const val MAX_LENGTH = 160

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    fun permitted(c: Context) = c.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun switchedOn(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false)

    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()

    fun text(c: Context): String = prefs(c).getString(KEY_TEXT, null)?.takeIf { it.isNotBlank() } ?: c.getString(R.string.ar_default)

    fun setText(c: Context, text: String) = prefs(c).edit().putString(KEY_TEXT, text.trim().take(MAX_LENGTH)).apply()

    /** A number a text can go to: at least 6 digits (not hidden, not a short service code). Pure. */
    fun textable(number: String?): Boolean = number != null && number.count { it.isDigit() } >= 6

    /** The call from [number] was declined by voice ([how]: for the log). Sends the text when on. */
    fun declined(context: Context, number: String?, how: String) {
        val c = context.applicationContext
        if (!switchedOn(c)) return
        if (!permitted(c)) return PacketLogger.log("AUTO REPLY: not sent ($how): the SMS permission is missing")
        if (!textable(number)) return PacketLogger.log("AUTO REPLY: not sent ($how): the number is hidden or unknown")
        runCatching {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) c.getSystemService(SmsManager::class.java)
                else @Suppress("DEPRECATION") SmsManager.getDefault()
            sms.sendTextMessage(number, null, text(c), null, null)
            PacketLogger.log("AUTO REPLY: text sent ($how)")
        }.onFailure { PacketLogger.error("auto reply", it) }
    }
}
