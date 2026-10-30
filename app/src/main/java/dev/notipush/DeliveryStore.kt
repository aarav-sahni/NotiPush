package dev.notipush

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Only pending payloads are stored, encrypted; history never keeps message text. */
class DeliveryStore(context: Context) : SQLiteOpenHelper(context, "notipush.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE deliveries (id INTEGER PRIMARY KEY AUTOINCREMENT, package TEXT NOT NULL, label TEXT NOT NULL, created INTEGER NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL, payload TEXT NOT NULL, timing TEXT NOT NULL DEFAULT '{}')")
        db.execSQL("CREATE INDEX deliveries_status ON deliveries(status)")
        db.execSQL("CREATE TABLE seen (fingerprint TEXT PRIMARY KEY, expires INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE deliveries ADD COLUMN timing TEXT NOT NULL DEFAULT '{}'")
    }

    @Synchronized fun enqueue(pkg: String, label: String, fingerprint: String, encrypted: String, dedupMillis: Long,
                              timing: DeliveryTiming = DeliveryTiming()): Long? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            prune(db)
            val now = System.currentTimeMillis()
            val seen = ContentValues().apply { put("fingerprint", fingerprint); put("expires", now + dedupMillis) }
            if (db.insertWithOnConflict("seen", null, seen, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                db.setTransactionSuccessful(); return null
            }
            val full = db.rawQuery("SELECT COUNT(*) FROM deliveries WHERE status='queued'", null).use { it.moveToFirst(); it.getInt(0) >= 500 }
            val id = db.insertOrThrow("deliveries", null, ContentValues().apply {
                put("package", pkg); put("label", label); put("created", now)
                put("status", if (full) "failed" else "queued")
                put("detail", if (full) "Queue full (500 messages)." else "Waiting for delivery")
                put("payload", if (full) "" else encrypted)
                put("timing", encodeTiming(timing))
            })
            db.setTransactionSuccessful()
            return if (full) null else id
        } finally { db.endTransaction() }
    }
    private fun row(cursor: Cursor) = Delivery(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
        cursor.getLong(3), cursor.getString(4), cursor.getString(5), cursor.getString(6), decodeTiming(cursor.getString(7)))
    private fun encodeTiming(t: DeliveryTiming) = JSONObject().put("postedAt", t.postedAt)
        .put("receivedAt", t.receivedAt).put("receivedState", t.receivedState)
        .put("firstAttemptAt", t.firstAttemptAt).put("firstAttemptState", t.firstAttemptState)
        .put("lastAttemptAt", t.lastAttemptAt).put("lastAttemptState", t.lastAttemptState)
        .put("completedAt", t.completedAt).put("completedState", t.completedState).put("attempts", t.attempts).toString()
    private fun decodeTiming(value: String): DeliveryTiming = runCatching {
        val t = JSONObject(value)
        DeliveryTiming(t.optLong("postedAt"), t.optLong("receivedAt"), t.optString("receivedState"),
            t.optLong("firstAttemptAt"), t.optString("firstAttemptState"), t.optLong("lastAttemptAt"),
            t.optString("lastAttemptState"), t.optLong("completedAt"), t.optString("completedState"), t.optInt("attempts"))
    }.getOrDefault(DeliveryTiming())
    @Synchronized fun recordAttempt(id: Long, state: String) {
        val row = get(id)?.takeIf { it.status == "queued" } ?: return
        val now = System.currentTimeMillis()
        val t = row.timing
        val updated = t.copy(firstAttemptAt = t.firstAttemptAt.takeIf { it > 0 } ?: now,
            firstAttemptState = if (t.firstAttemptAt > 0) t.firstAttemptState else state,
            lastAttemptAt = now, lastAttemptState = state, attempts = t.attempts + 1)
        writableDatabase.update("deliveries", ContentValues().apply { put("timing", encodeTiming(updated)) },
            "id=? AND status='queued'", arrayOf(id.toString()))
    }
    @Synchronized fun recordCompletion(id: Long, state: String) {
        val row = get(id)?.takeIf { it.status in setOf("sent", "failed") } ?: return
        if (row.timing.completedAt > 0) return
        val updated = row.timing.copy(completedAt = System.currentTimeMillis(), completedState = state)
        writableDatabase.update("deliveries", ContentValues().apply { put("timing", encodeTiming(updated)) },
            "id=?", arrayOf(id.toString()))
    }
    @Synchronized fun get(id: Long): Delivery? = readableDatabase.rawQuery("SELECT * FROM deliveries WHERE id=?", arrayOf(id.toString())).use {
        if (it.moveToFirst()) row(it) else null
    }
    @Synchronized fun pending(): List<Long> {
        prune(writableDatabase)
        return readableDatabase.rawQuery("SELECT id FROM deliveries WHERE status='queued' ORDER BY id", null).use {
            buildList { while (it.moveToNext()) add(it.getLong(0)) }
        }
    }
    @Synchronized fun finish(id: Long, status: String, detail: String) {
        writableDatabase.update("deliveries", ContentValues().apply {
            put("status", status); put("detail", detail); put("payload", "")
        }, "id=? AND status='queued'", arrayOf(id.toString()))
    }
    @Synchronized fun retryDetail(id: Long, detail: String) {
        writableDatabase.update("deliveries", ContentValues().apply { put("detail", detail) },
            "id=? AND status='queued'", arrayOf(id.toString()))
    }
    @Synchronized fun cancelPending(reason: String, packageName: String? = null) {
        writableDatabase.update("deliveries", ContentValues().apply {
            put("status", "cancelled"); put("detail", reason); put("payload", "")
        }, "status='queued'" + if (packageName == null) "" else " AND package=?",
            if (packageName == null) null else arrayOf(packageName))
    }
    @Synchronized fun history(): List<Delivery> {
        prune(writableDatabase)
        return readableDatabase.rawQuery("SELECT * FROM deliveries ORDER BY id DESC LIMIT 200", null).use {
            buildList { while (it.moveToNext()) add(row(it)) }
        }
    }
    @Synchronized fun stats(): DeliveryStats {
        prune(writableDatabase)
        fun count(status: String) = readableDatabase.rawQuery("SELECT COUNT(*) FROM deliveries WHERE status=?", arrayOf(status)).use { it.moveToFirst(); it.getInt(0) }
        return DeliveryStats(count("sent"), count("queued"), count("failed"))
    }
    @Synchronized fun clearHistory() { writableDatabase.delete("deliveries", "status!='queued'", null) }
    @Synchronized fun erase() { writableDatabase.delete("deliveries", null, null); writableDatabase.delete("seen", null, null) }
    private fun prune(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        db.delete("seen", "expires<?", arrayOf(now.toString()))
        db.execSQL("UPDATE deliveries SET status='failed',detail='Expired after 24 hours offline',payload='' WHERE status='queued' AND created<?", arrayOf(now - 86_400_000))
        db.execSQL("DELETE FROM deliveries WHERE status!='queued' AND id NOT IN (SELECT id FROM deliveries WHERE status!='queued' ORDER BY id DESC LIMIT 200)")
    }
}
