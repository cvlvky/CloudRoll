package com.cvlvky.cloudroll.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** A record of one phone photo or video already in the backup channel. */
data class BackedUp(val key: String, val mediaId: Long, val uri: String, val size: Long, val messageId: Long)

/** Small local database: what is backed up, and what was saved to the phone. */
object Db {
    private lateinit var helper: SQLiteOpenHelper

    fun init(context: Context) {
        if (::helper.isInitialized) return
        helper = object : SQLiteOpenHelper(context.applicationContext, "cloudroll.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE backup (key TEXT PRIMARY KEY, media_id INTEGER, uri TEXT, size INTEGER, " +
                        "message_id INTEGER, chat_id INTEGER, at INTEGER)",
                )
                db.execSQL("CREATE TABLE saved (key TEXT PRIMARY KEY, at INTEGER)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    fun backedUpKeys(chatId: Long): Set<String> {
        val out = HashSet<String>()
        db.rawQuery("SELECT key FROM backup WHERE chat_id = ?", arrayOf(chatId.toString())).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun backedUpCount(chatId: Long): Int =
        db.rawQuery("SELECT COUNT(*) FROM backup WHERE chat_id = ?", arrayOf(chatId.toString())).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun backedUp(chatId: Long): List<BackedUp> {
        val out = ArrayList<BackedUp>()
        db.rawQuery(
            "SELECT key, media_id, uri, size, message_id FROM backup WHERE chat_id = ?",
            arrayOf(chatId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(BackedUp(c.getString(0), c.getLong(1), c.getString(2), c.getLong(3), c.getLong(4)))
            }
        }
        return out
    }

    fun markBackedUp(key: String, mediaId: Long, uri: String, size: Long, chatId: Long, messageId: Long) {
        val v = ContentValues().apply {
            put("key", key)
            put("media_id", mediaId)
            put("uri", uri)
            put("size", size)
            put("chat_id", chatId)
            put("message_id", messageId)
            put("at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("backup", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearBackups() {
        db.delete("backup", null, null)
    }

    fun isSaved(key: String): Boolean =
        db.rawQuery("SELECT 1 FROM saved WHERE key = ?", arrayOf(key)).use { it.moveToFirst() }

    fun markSaved(key: String) {
        val v = ContentValues().apply {
            put("key", key)
            put("at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("saved", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearSaved() {
        db.delete("saved", null, null)
    }
}
