package com.spizganed.quickbuds.usage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate

/**
 * The usage store on disk: `usage.db`, Android's own SQLite (no library). Three tables:
 *
 *  - `device`: one row per pair of buds (Bluetooth address), its names and what it was seen to support;
 *  - `session`: one row per connection, kept for the retention period ([UsageRetention]);
 *  - `day_summary`: rolled-up days, one row per day and device.
 *
 * Local only: excluded from Android backup (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`).
 * Schema changes go through [onUpgrade], one step per version, never a drop.
 */
class UsageDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION), UsageStore {

    override fun onConfigure(db: SQLiteDatabase) {
        // One writer thread ([Usage]); WAL keeps a screen's reads from waiting on it.
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE device (id TEXT PRIMARY KEY, name TEXT, model TEXT, " +
                "placement INTEGER NOT NULL DEFAULT -1, rssi INTEGER NOT NULL DEFAULT -1, kind TEXT)"
        )
        db.execSQL(
            "CREATE TABLE session (id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT NOT NULL, " +
                "start_ms INTEGER NOT NULL, end_ms INTEGER, last_seen_ms INTEGER NOT NULL, " +
                "start_elapsed INTEGER NOT NULL, boot_count INTEGER NOT NULL, boot_wall INTEGER NOT NULL, " +
                "created_ms INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX session_device_end ON session(device_id, end_ms)")
        db.execSQL(
            "CREATE TABLE day_summary (day TEXT NOT NULL, device_id TEXT NOT NULL, total_ms INTEGER NOT NULL, " +
                "sessions INTEGER NOT NULL, started INTEGER NOT NULL, longest_ms INTEGER NOT NULL, " +
                "PRIMARY KEY (day, device_id))"
        )
    }

    /** One step per version, in order, keeping every row. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 2: the kind of device (TWS, neckband, headphones, speaker), learned at connect time.
        if (oldVersion < 2) db.execSQL("ALTER TABLE device ADD COLUMN kind TEXT")
    }

    /** A build with an older schema than the file (installing an older version over a newer one): start over. */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS device")
        db.execSQL("DROP TABLE IF EXISTS session")
        db.execSQL("DROP TABLE IF EXISTS day_summary")
        onCreate(db)
    }

    // --- Devices ---

    override fun upsertDevice(device: UsageDevice) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("id", device.id)
            if (device.name != null) put("name", device.name)
            if (device.model != null) put("model", device.model)
            if (device.kind != null) put("kind", device.kind)
        }
        // Keep a known name or model when this event does not carry one.
        if (db.update("device", values, "id = ?", arrayOf(device.id)) == 0) db.insert("device", null, values)
    }

    override fun devices(): List<UsageDevice> = readableDatabase
        .rawQuery("SELECT id, name, model, kind FROM device ORDER BY rowid", null).use { c ->
            c.rows { UsageDevice(it.getString(0), it.stringOrNull(1), it.stringOrNull(2), it.stringOrNull(3)) }
        }

    override fun capabilities(deviceId: String): UsageCapabilities = readableDatabase
        .rawQuery("SELECT placement, rssi FROM device WHERE id = ?", arrayOf(deviceId)).use { c ->
            if (!c.moveToFirst()) UsageCapabilities() else UsageCapabilities(c.getInt(0).toFlag(), c.getInt(1).toFlag())
        }

    override fun saveCapabilities(deviceId: String, caps: UsageCapabilities) {
        val values = ContentValues().apply {
            put("placement", caps.placementReports.toInt())
            put("rssi", caps.rssi.toInt())
        }
        val db = writableDatabase
        if (db.update("device", values, "id = ?", arrayOf(deviceId)) == 0) {
            values.put("id", deviceId)
            db.insert("device", null, values)
        }
    }

    // --- Sessions ---

    override fun openSession(deviceId: String): UsageSession? =
        querySessions("device_id = ? AND end_ms IS NULL ORDER BY id DESC LIMIT 1", deviceId).firstOrNull()

    override fun openSessions(): List<UsageSession> = querySessions("end_ms IS NULL ORDER BY id")

    override fun lastClosedSession(deviceId: String): UsageSession? =
        querySessions("device_id = ? AND end_ms IS NOT NULL ORDER BY end_ms DESC LIMIT 1", deviceId).firstOrNull()

    override fun insertSession(session: UsageSession): Long = writableDatabase.insert("session", null, ContentValues().apply {
        put("device_id", session.deviceId)
        put("start_ms", session.startMs)
        if (session.endMs != null) put("end_ms", session.endMs) else putNull("end_ms")
        put("last_seen_ms", session.lastSeenMs)
        put("start_elapsed", session.startElapsed)
        put("boot_count", session.boot.count)
        put("boot_wall", session.boot.wallAtBoot)
        put("created_ms", System.currentTimeMillis())
    })

    override fun closeSession(id: Long, endMs: Long) {
        // `end_ms IS NULL`: closing twice keeps the first end.
        writableDatabase.update("session", ContentValues().apply {
            put("end_ms", endMs)
            put("last_seen_ms", endMs)
        }, "id = ? AND end_ms IS NULL", arrayOf(id.toString()))
    }

    override fun reopenSession(id: Long, lastSeenMs: Long) {
        writableDatabase.update("session", ContentValues().apply {
            putNull("end_ms")
            put("last_seen_ms", lastSeenMs)
        }, "id = ?", arrayOf(id.toString()))
    }

    override fun touchSession(id: Long, lastSeenMs: Long) {
        writableDatabase.update("session", ContentValues().apply { put("last_seen_ms", lastSeenMs) },
            "id = ? AND end_ms IS NULL", arrayOf(id.toString()))
    }

    override fun sessions(): List<UsageSession> = querySessions("1 ORDER BY start_ms")

    override fun closedSessionsEndingBefore(ms: Long): List<UsageSession> =
        querySessions("end_ms IS NOT NULL AND end_ms < ? ORDER BY start_ms", ms.toString())

    // --- Day summaries ---

    override fun rollUp(sessionIds: List<Long>, summaries: List<DaySummary>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Merge into an existing row, else insert. Not `ON CONFLICT ... DO UPDATE`: Android 8-10 ship
            // SQLite older than 3.24, which lacks it.
            for (s in summaries) {
                val merge = db.compileStatement(
                    "UPDATE day_summary SET total_ms = total_ms + ?, sessions = sessions + ?, started = started + ?, " +
                        "longest_ms = MAX(longest_ms, ?) WHERE day = ? AND device_id = ?"
                )
                merge.bindLong(1, s.totalMs); merge.bindLong(2, s.sessions.toLong()); merge.bindLong(3, s.started.toLong())
                merge.bindLong(4, s.longestMs); merge.bindString(5, s.date.toString()); merge.bindString(6, s.deviceId)
                if (merge.executeUpdateDelete() == 0) db.insert("day_summary", null, ContentValues().apply {
                    put("day", s.date.toString()); put("device_id", s.deviceId); put("total_ms", s.totalMs)
                    put("sessions", s.sessions); put("started", s.started); put("longest_ms", s.longestMs)
                })
                merge.close()
            }
            for (id in sessionIds) db.delete("session", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun summaries(): List<DaySummary> = readableDatabase.rawQuery(
        "SELECT day, device_id, total_ms, sessions, started, longest_ms FROM day_summary ORDER BY day", null
    ).use { c ->
        c.rows { DaySummary(LocalDate.parse(it.getString(0)), it.getString(1), it.getLong(2), it.getInt(3), it.getInt(4), it.getLong(5)) }
    }

    override fun deleteSummariesBefore(date: LocalDate) {
        // ISO dates sort as text.
        writableDatabase.delete("day_summary", "day < ?", arrayOf(date.toString()))
    }

    private fun querySessions(where: String, vararg args: String): List<UsageSession> = readableDatabase.rawQuery(
        "SELECT id, device_id, start_ms, end_ms, last_seen_ms, start_elapsed, boot_count, boot_wall FROM session WHERE $where",
        args
    ).use { c ->
        c.rows {
            UsageSession(
                it.getLong(0), it.getString(1), it.getLong(2), if (it.isNull(3)) null else it.getLong(3),
                it.getLong(4), it.getLong(5), BootStamp(it.getInt(6), it.getLong(7))
            )
        }
    }

    private fun <T> Cursor.rows(read: (Cursor) -> T): List<T> {
        val out = ArrayList<T>(count)
        while (moveToNext()) out += read(this)
        return out
    }

    private fun Cursor.stringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
    private fun Int.toFlag(): Boolean? = if (this < 0) null else this == 1
    private fun Boolean?.toInt(): Int = when (this) { null -> -1; true -> 1; false -> 0 }

    companion object {
        const val NAME = "usage.db"
        const val VERSION = 2
    }
}
