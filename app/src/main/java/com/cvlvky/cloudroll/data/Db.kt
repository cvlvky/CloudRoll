package com.cvlvky.cloudroll.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** A record of one phone photo or video already in the backup channel. */
data class BackedUp(val key: String, val mediaId: Long, val uri: String, val size: Long, val messageId: Long)

/** Where a Telegram video was stopped, for Continue watching. */
data class Position(val key: String, val chatId: Long, val messageId: Long, val position: Long, val duration: Long, val updated: Long, val title: String)

/** A file name's movie on TMDB, or no movie (id 0), remembered so it is looked up once. */
data class Match(val nameKey: String, val tmdbId: Int, val title: String, val year: Int, val poster: String, val checkedAt: Long)

/** Small local database: what is backed up, and what was saved to the phone. */
object Db {
    private lateinit var helper: SQLiteOpenHelper

    fun init(context: Context) {
        if (::helper.isInitialized) return
        helper = object : SQLiteOpenHelper(context.applicationContext, "cloudroll.db", null, 2) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE backup (key TEXT PRIMARY KEY, media_id INTEGER, uri TEXT, size INTEGER, " +
                        "message_id INTEGER, chat_id INTEGER, at INTEGER)",
                )
                db.execSQL("CREATE TABLE saved (key TEXT PRIMARY KEY, at INTEGER)")
                createV2(db)
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                if (oldVersion < 2) createV2(db)
            }

            private fun createV2(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS positions (key TEXT PRIMARY KEY, chat_id INTEGER, message_id INTEGER, " +
                        "position INTEGER, duration INTEGER, updated INTEGER, title TEXT)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS matches (name_key TEXT PRIMARY KEY, tmdb_id INTEGER, title TEXT, " +
                        "year INTEGER, poster TEXT, checked_at INTEGER)",
                )
            }
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

    // ---- Resume positions ----

    fun savePosition(key: String, chatId: Long, messageId: Long, position: Long, duration: Long, title: String) {
        val v = ContentValues().apply {
            put("key", key)
            put("chat_id", chatId)
            put("message_id", messageId)
            put("position", position)
            put("duration", duration)
            put("updated", System.currentTimeMillis())
            put("title", title)
        }
        db.insertWithOnConflict("positions", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun position(key: String): Position? =
        db.rawQuery(
            "SELECT key, chat_id, message_id, position, duration, updated, title FROM positions WHERE key = ?",
            arrayOf(key),
        ).use { c -> if (c.moveToFirst()) readPosition(c) else null }

    /** Videos stopped part way, newest first. */
    fun continueWatching(limit: Int = 30): List<Position> {
        val out = ArrayList<Position>()
        db.rawQuery(
            "SELECT key, chat_id, message_id, position, duration, updated, title FROM positions " +
                "WHERE position > 30000 AND (duration <= 0 OR position < duration * 0.94) ORDER BY updated DESC LIMIT $limit",
            null,
        ).use { c -> while (c.moveToNext()) out.add(readPosition(c)) }
        return out
    }

    fun removePosition(key: String) {
        db.delete("positions", "key = ?", arrayOf(key))
    }

    private fun readPosition(c: android.database.Cursor) =
        Position(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5), c.getString(6) ?: "")

    // ---- Movie matches ----

    fun match(nameKey: String): Match? =
        db.rawQuery(
            "SELECT name_key, tmdb_id, title, year, poster, checked_at FROM matches WHERE name_key = ?",
            arrayOf(nameKey),
        ).use { c -> if (c.moveToFirst()) Match(c.getString(0), c.getInt(1), c.getString(2) ?: "", c.getInt(3), c.getString(4) ?: "", c.getLong(5)) else null }

    fun saveMatch(m: Match) {
        val v = ContentValues().apply {
            put("name_key", m.nameKey)
            put("tmdb_id", m.tmdbId)
            put("title", m.title)
            put("year", m.year)
            put("poster", m.poster)
            put("checked_at", m.checkedAt)
        }
        db.insertWithOnConflict("matches", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearMatches() {
        db.delete("matches", null, null)
    }
}
