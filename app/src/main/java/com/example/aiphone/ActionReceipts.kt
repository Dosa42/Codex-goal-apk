package com.example.aiphone

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** Persistent immutable mutation journal. A crash after dispatch is uncertain, never replayed. */
class ActionReceipts(context: Context): SQLiteOpenHelper(context,"ai-phone-actions.sqlite",null,1), java.io.Closeable {
    class Conflict(val code: String, message: String): Exception(message)
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE receipts(id TEXT PRIMARY KEY, request TEXT NOT NULL, response TEXT)") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported journal migration") }
    private fun canonical(value: Any?): String = when(value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix="{",postfix="}") { JSONObject.quote(it)+":"+canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(prefix="[",postfix="]") { canonical(value.get(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    fun lookup(id: String, request: JSONObject): JSONObject? {
        readableDatabase.query("receipts",arrayOf("request","response"),"id=?",arrayOf(id),null,null,null).use { c ->
            if(!c.moveToFirst()) return null
            if(c.getString(0)!=canonical(request)) throw Conflict("request_conflict","Request ID already belongs to different immutable content")
            if(c.isNull(1)) throw Conflict("uncertain_action","Prior dispatch has no durable result; do not replay")
            return JSONObject(c.getString(1))
        }
    }
    fun begin(id: String, request: JSONObject) {
        writableDatabase.insertOrThrow("receipts",null,ContentValues().apply { put("id",id);put("request",canonical(request)) })
    }
    fun complete(id: String, request: JSONObject, response: JSONObject) {
        val changed=writableDatabase.update("receipts",ContentValues().apply { put("response",response.toString()) },"id=? AND request=? AND response IS NULL",arrayOf(id,canonical(request)))
        check(changed==1) { "Receipt completion did not match dispatched request" }
    }
}
