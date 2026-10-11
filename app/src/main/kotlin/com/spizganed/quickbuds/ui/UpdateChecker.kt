package com.spizganed.quickbuds.ui

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.spizganed.quickbuds.R
import org.json.JSONObject
import java.io.File

/**
 * GitHub release lookup for the in-app updater, shared by [UpdateActivity], the popup on start and the
 * background check (a notification). The updater needs the release's `.apk` asset; the `.aab` alone is invisible
 * to it (CLAUDE.md).
 */
object UpdateChecker {

    private const val REPO = "MageshwarSV/QuickBuds"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=30"
    const val PAGE = "https://github.com/$REPO"

    /** App update › Check for updates (default on): the popup on start and the background notification. */
    const val KEY_AUTO = "updateAutoCheck"
    private const val KEY_LAST_CHECK = "updateLastCheck"
    private const val KEY_BACKGROUND_CHECK = "updateBackgroundCheck"
    /** The version the popup showed (viewed or ignored): never shown again, and no notification for it. */
    private const val KEY_ANNOUNCED = "updateAnnounced"
    /** The version the background check found and notified. */
    private const val KEY_NOTIFIED = "updateNotified"
    private const val MIN_INTERVAL_MS = 12 * 60 * 60 * 1000L

    const val CHANNEL = "QuickBuds_Update"
    private const val NOTIFICATION_ID = 9

    data class Release(val tag: String, val apkUrl: String?, val notes: String)

    fun installed(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        com.spizganed.quickbuds.bluetooth.PacketLogger.error("installed version", e)
        "0"
    }

    /** Blocking; call off the main thread. */
    fun fetch(): Release {
        val conn = java.net.URL(API).openConnection() as java.net.HttpURLConnection
        val body = try {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        // Newest first. Desktop releases (`desktop-v*`) and pre-releases share the repo; skip them.
        val list = org.json.JSONArray(body)
        for (i in 0 until list.length()) {
            val json = list.getJSONObject(i)
            val tag = json.getString("tag_name")
            if (!tag.startsWith("v") || json.optBoolean("prerelease")) continue
            val assets = json.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0))
                .map { assets!!.getJSONObject(it).optString("browser_download_url") }
                .firstOrNull { it.endsWith(".apk", ignoreCase = true) }
                ?: continue
            return Release(tag, apk, json.optString("body").takeIf { it != "null" }.orEmpty())
        }
        throw IllegalStateException("No Android release")
    }

    /** Numeric compare of "v2.1.0" against "2.1.0-debug"; suffixes are ignored. */
    fun isNewer(remote: String, local: String): Boolean {
        val a = remote.trim().removePrefix("v").substringBefore('-').split('.')
        val b = local.trim().removePrefix("v").substringBefore('-').split('.')
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i)?.toIntOrNull() ?: 0
            val y = b.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x > y
        }
        return false
    }

    /** Streams [url] into [dest], reporting 0..100 (or -1 when the size is unknown). Blocking. */
    fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -2
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Check on start: at most every 12 h, silent on any failure, and one popup per new version (View or Ignore),
     * never again for the same tag. A version the background check already found shows at once, without a fetch.
     */
    fun maybeAutoCheck(activity: Activity) {
        // The last update's download (UpdateActivity), ~2.5 MB, has been installed or abandoned by now.
        java.io.File(activity.cacheDir, "quickbuds-update.apk").delete()
        val prefs = prefs(activity)
        if (!prefs.getBoolean(KEY_AUTO, true)) return
        val installed = installed(activity)
        prefs.getString(KEY_NOTIFIED, null)?.let { found ->
            if (found != prefs.getString(KEY_ANNOUNCED, null) && isNewer(found, installed)) return popup(activity, found, installed)
        }
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < MIN_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        Thread {
            val release = runCatching { fetch() }.getOrNull() ?: return@Thread
            if (release.apkUrl == null || !isNewer(release.tag, installed)) return@Thread
            if (prefs.getString(KEY_ANNOUNCED, null) == release.tag) return@Thread
            Handler(Looper.getMainLooper()).post { popup(activity, release.tag, installed) }
        }.apply { isDaemon = true }.start()
    }

    /** "QuickBuds X is available": View opens the update screen, Ignore skips this version. Either way, once. */
    private fun popup(activity: Activity, tag: String, installed: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        prefs(activity).edit().putString(KEY_ANNOUNCED, tag).apply()
        clearNotification(activity)
        ConfirmDialog.show(
            activity,
            activity.getString(R.string.update_found_title, tag.removePrefix("v")),
            activity.getString(R.string.update_found_body, installed),
            activity.getString(R.string.update_found_view),
            cancelRes = R.string.update_ignore
        ) { activity.startActivity(Intent(activity, UpdateActivity::class.java)) }
    }

    /**
     * From [com.spizganed.quickbuds.bluetooth.BudsService], so it runs with the app closed: at most every 12 h,
     * silent on any failure. A new version posts one notification; a tap opens the update screen, a swipe ignores it.
     */
    fun backgroundCheck(context: Context) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_AUTO, true)) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_BACKGROUND_CHECK, 0L) < MIN_INTERVAL_MS) return
        prefs.edit().putLong(KEY_BACKGROUND_CHECK, now).apply()
        val app = context.applicationContext
        val installed = installed(app)
        Thread {
            val release = runCatching { fetch() }.getOrNull() ?: return@Thread
            if (release.apkUrl == null || !isNewer(release.tag, installed)) return@Thread
            if (release.tag == prefs.getString(KEY_NOTIFIED, null) || release.tag == prefs.getString(KEY_ANNOUNCED, null)) return@Thread
            prefs.edit().putString(KEY_NOTIFIED, release.tag).apply()
            notify(app, release.tag, installed)
        }.apply { isDaemon = true }.start()
    }

    private fun notify(c: Context, tag: String, installed: String) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.update_channel),
                NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(c, NOTIFICATION_ID,
            Intent(c, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buds)
            .setContentTitle(c.getString(R.string.update_found_title, tag.removePrefix("v")))
            .setContentText(c.getString(R.string.update_found_body, installed))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
            .onFailure { com.spizganed.quickbuds.bluetooth.PacketLogger.error("update notification", it) }
    }

    /** The update screen is open, or the popup showed: the notification has said its piece. */
    fun clearNotification(c: Context) {
        c.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
