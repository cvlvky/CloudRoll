package com.cvlvky.cloudroll.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One saved setting, readable as a flow so screens redraw when it changes. */
class Pref<T>(
    private val sp: () -> SharedPreferences,
    private val key: String,
    private val default: T,
    private val read: SharedPreferences.(String, T) -> T,
    private val write: SharedPreferences.Editor.(String, T) -> Unit,
) {
    private val state by lazy { MutableStateFlow(sp().read(key, default)) }
    val flow: StateFlow<T> get() = state
    var value: T
        get() = state.value
        set(v) {
            state.value = v
            sp().edit().apply { write(key, v) }.apply()
        }
}

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences("cloudroll", Context.MODE_PRIVATE)
    }

    private fun bool(key: String, d: Boolean) = Pref({ sp }, key, d, { k, v -> getBoolean(k, v) }, { k, v -> putBoolean(k, v) })
    private fun int(key: String, d: Int) = Pref({ sp }, key, d, { k, v -> getInt(k, v) }, { k, v -> putInt(k, v) })
    private fun long(key: String, d: Long) = Pref({ sp }, key, d, { k, v -> getLong(k, v) }, { k, v -> putLong(k, v) })
    private fun str(key: String, d: String) = Pref({ sp }, key, d, { k, v -> getString(k, v) ?: v }, { k, v -> putString(k, v) })
    private fun strings(key: String) = Pref<Set<String>>(
        { sp }, key, emptySet(),
        { k, v -> getStringSet(k, v)?.toSet() ?: v },
        { k, v -> putStringSet(k, v) },
    )

    // Telegram API keys from my.telegram.org. They stay on this phone only.
    val apiId = int("api_id", 0)
    val apiHash = str("api_hash", "")

    data class ApiKeys(val id: Int, val hash: String)

    fun apiKeys(): ApiKeys? {
        val id = apiId.value
        val hash = apiHash.value
        return if (id > 0 && hash.length >= 16) ApiKeys(id, hash) else null
    }

    // Appearance: 0 follows the phone, 1 light, 2 dark.
    val theme = int("theme", 0)
    val dynamicColor = bool("dynamic_color", true)

    // Privacy
    val appLock = bool("app_lock", false)
    val hiddenChats = strings("hidden_chats")
    val favourites = strings("favourites")

    // Storage: how much TDLib may keep as cache, in GB.
    val cacheLimitGb = int("cache_limit_gb", 2)

    // Backup
    val backupEnabled = bool("backup_enabled", false)
    val backupFolders = strings("backup_folders")
    val backupVideos = bool("backup_videos", true)
    val backupWifiOnly = bool("backup_wifi_only", true)
    val backupChargingOnly = bool("backup_charging_only", false)
    val backupChatId = long("backup_chat_id", 0L)
    val backupLastRun = long("backup_last_run", 0L)

    // Small one-time markers
    val askedNotifications = bool("asked_notifications", false)

    // Movies (TMDB and OMDb keys are free; the same keys as ReelPulse work here)
    val tmdbKey = str("tmdb_key", "")
    val omdbKey = str("omdb_key", "")
    val movieLangs = strings("movie_langs")
    val movieRegion = str("movie_region", "IN")
    val findMovies = bool("find_movies", true)

    // Gallery and viewer
    val gridColumns = int("grid_columns", 4)
    val starred = strings("starred")
    val resumeVideos = bool("resume_videos", true)
    val pip = bool("pip", true)

    /** The movie languages chosen, Malayalam, Tamil and English when none were chosen. */
    fun langs(): List<String> = movieLangs.value.toList().ifEmpty { listOf("ml", "ta", "en") }

    fun isStarred(key: String) = starred.value.contains(key)

    fun toggleStarred(key: String) {
        val s = starred.value.toMutableSet()
        if (!s.remove(key)) s.add(key)
        starred.value = s
    }

    fun setStarred(keys: Collection<String>, on: Boolean) {
        val s = starred.value.toMutableSet()
        if (on) s.addAll(keys) else s.removeAll(keys.toSet())
        starred.value = s
    }

    fun isHidden(chatId: Long) = hiddenChats.value.contains(chatId.toString())

    fun toggleHidden(chatId: Long) {
        val s = hiddenChats.value.toMutableSet()
        if (!s.remove(chatId.toString())) s.add(chatId.toString())
        hiddenChats.value = s
    }

    fun isFavourite(key: String) = favourites.value.contains(key)

    fun toggleFavourite(key: String) {
        val s = favourites.value.toMutableSet()
        if (!s.remove(key)) s.add(key)
        favourites.value = s
    }

    /** What belongs to the signed-in account only, cleared when signing out. */
    fun clearAccountData() {
        backupChatId.value = 0L
        backupLastRun.value = 0L
        favourites.value = emptySet()
        hiddenChats.value = emptySet()
        starred.value = emptySet()
    }
}
