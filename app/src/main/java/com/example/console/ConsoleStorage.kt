package com.example.console

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/** Endpoint, event history and cursor are separate from all executor preferences. */
class ConsoleStorage(context: Context) : SQLiteOpenHelper(context, "goal-console.db", null, 3) {
    private val prefs = context.getSharedPreferences("goal-console", Context.MODE_PRIVATE)
    private val snapshotFile = AtomicFile(File(context.filesDir, "goal-console-last-snapshot.json"))
    var endpoint: String
        get() = prefs.getString("endpoint", ConsoleClient.DEFAULT_ENDPOINT)!!
        set(value) { prefs.edit().putString("endpoint", value).commit() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cursors(endpoint TEXT PRIMARY KEY, seq INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE events(endpoint TEXT NOT NULL, seq INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(endpoint,seq))")
        db.execSQL("CREATE TABLE actions(id INTEGER PRIMARY KEY AUTOINCREMENT, endpoint TEXT NOT NULL, label TEXT NOT NULL, body TEXT NOT NULL, outcome TEXT NOT NULL, operation_id TEXT)")
        db.execSQL("CREATE TABLE archives(endpoint TEXT NOT NULL, instance TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY(endpoint,instance))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS archives(endpoint TEXT NOT NULL, instance TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY(endpoint,instance))")
        if (oldVersion < 3) db.execSQL("ALTER TABLE actions ADD COLUMN operation_id TEXT")
    }

    companion object {
        fun archiveKey(endpoint: String, instance: String): String =
            if (instance == "legacy") endpoint else "$endpoint\n$instance"
    }

    @Synchronized fun registerArchive(endpoint: String, instance: String): List<String> {
        if (cursor(endpoint) > 0) {
            writableDatabase.insertWithOnConflict("archives", null, ContentValues().apply {
                put("endpoint", endpoint); put("instance", "legacy"); put("created", 0L)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
        writableDatabase.insertWithOnConflict("archives", null, ContentValues().apply {
            put("endpoint", endpoint); put("instance", instance); put("created", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        check(prefs.edit().putString("last-instance:$endpoint", instance).commit()) { "Unable to persist console instance" }
        return archives(endpoint)
    }

    @Synchronized fun archives(endpoint: String): List<String> =
        readableDatabase.query("archives", arrayOf("instance"), "endpoint=?", arrayOf(endpoint), null, null, "created DESC").use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }

    @Synchronized fun lastInstance(endpoint: String): String =
        prefs.getString("last-instance:$endpoint", null) ?: archives(endpoint).firstOrNull().orEmpty()

    @Synchronized fun ceoDraft(): JsonObject {
        val saved = prefs.getString("ceoDraft", null)
        if (saved != null) return ConsoleJson.read(saved)
        val value = mapOf("messageId" to "apk-${UUID.randomUUID()}", "text" to "", "submitted" to false)
        saveCeoDraft(value)
        return value
    }
    @Synchronized fun saveCeoDraft(value: JsonObject) {
        check(prefs.edit().putString("ceoDraft", ConsoleJson.write(value)).commit()) { "Unable to persist CEO draft" }
    }

    @Synchronized fun saveSnapshot(endpoint: String, snapshot: JsonObject, stale: Boolean, errors: Map<String, String>) {
        val value = mapOf(
            "endpoint" to endpoint, "savedAtEpochMs" to System.currentTimeMillis(),
            "observedAt" to snapshot["observedAt"], "stale" to stale,
            "snapshot" to snapshot, "clientErrors" to errors
        )
        val stream = snapshotFile.startWrite()
        try {
            stream.write(ConsoleJson.write(value).toByteArray(Charsets.UTF_8))
            snapshotFile.finishWrite(stream)
        } catch (e: Exception) { snapshotFile.failWrite(stream); throw e }
    }

    @Synchronized fun cursor(endpoint: String): Long =
        readableDatabase.rawQuery("SELECT seq FROM cursors WHERE endpoint=?", arrayOf(endpoint)).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    /** Atomic journal append and cursor advance. Cancellation/failure never skips a page. */
    @Synchronized fun append(endpoint: String, page: EventPage) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previous = cursor(endpoint)
            require(page.cursor >= previous) { "Cursor regression" }
            for (event in page.events) {
                val seq = requireNotNull(event.number("seq"))
                val row = ContentValues().apply {
                    put("endpoint", endpoint); put("seq", seq); put("body", ConsoleJson.write(event))
                }
                db.insertWithOnConflict("events", null, row, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.insertWithOnConflict("cursors", null, ContentValues().apply {
                put("endpoint", endpoint); put("seq", page.cursor)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun events(endpoint: String, before: Long? = null, limit: Int = 100): List<JsonObject> {
        val where = if (before == null) "endpoint=?" else "endpoint=? AND seq<?"
        val args = if (before == null) arrayOf(endpoint) else arrayOf(endpoint, before.toString())
        return readableDatabase.query("events", arrayOf("body"), where, args, null, null, "seq DESC", limit.toString()).use {
            buildList { while (it.moveToNext()) add(ConsoleJson.read(it.getString(0))) }.reversed()
        }
    }

    @Synchronized fun beginAction(endpoint: String, label: String, body: JsonObject, operationId: String = UUID.randomUUID().toString()): Long =
        writableDatabase.insertOrThrow("actions", null, ContentValues().apply {
            put("endpoint", endpoint); put("label", label); put("body", ConsoleJson.write(body))
            put("operation_id", operationId)
            put("outcome", "Dispatch recorded; outcome unknown until a response is received. Do not automatically replay.")
        })

    @Synchronized fun finishAction(id: Long, outcome: String) {
        writableDatabase.update("actions", ContentValues().apply { put("outcome", outcome) }, "id=?", arrayOf(id.toString()))
    }

    @Synchronized fun recordReceipt(endpoint: String, operationId: String, response: JsonObject) {
        writableDatabase.update("actions", ContentValues().apply { put("outcome", ConsoleJson.write(response)) },
            "endpoint=? AND operation_id=?", arrayOf(endpoint, operationId))
    }

    @Synchronized fun actions(endpoint: String, before: Long? = null): List<JsonObject> {
        val where = if (before == null) "endpoint=?" else "endpoint=? AND id<?"
        val args = if (before == null) arrayOf(endpoint) else arrayOf(endpoint, before.toString())
        return readableDatabase.query("actions", arrayOf("id", "label", "body", "outcome", "operation_id"), where, args, null, null, "id DESC", "50").use {
            buildList {
                while (it.moveToNext()) add(mapOf(
                    "id" to it.getLong(0), "label" to it.getString(1),
                    "request" to ConsoleJson.read(it.getString(2)), "outcome" to it.getString(3), "operationId" to it.getString(4)
                ))
            }
        }
    }
}
