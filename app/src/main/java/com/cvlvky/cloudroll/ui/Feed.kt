package com.cvlvky.cloudroll.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cvlvky.cloudroll.tg.MediaFilter
import com.cvlvky.cloudroll.tg.Page
import com.cvlvky.cloudroll.tg.Repo
import com.cvlvky.cloudroll.tg.Tg
import com.cvlvky.cloudroll.tg.TgMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Screens load on this scope, so leaving a screen does not throw away what was loaded. */
val UiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** A list that loads page by page from Telegram as it is scrolled. */
class Feed(private val loader: suspend (cursor: String) -> Page) {
    val items = mutableStateListOf<TgMedia>()
    var loading by mutableStateOf(false)
        private set
    var done by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var cursor = ""
    private var job: Job? = null
    private val seen = HashSet<String>()

    fun loadMore() {
        if (loading || done) return
        loading = true
        error = null
        job = UiScope.launch {
            try {
                val page = loader(cursor)
                page.items.forEach { if (seen.add(it.key)) items.add(it) }
                cursor = page.next.orEmpty()
                if (page.next == null) done = true
            } catch (e: Exception) {
                error = e.message?.let { Tg.humanize(it) } ?: "Could not load"
            } finally {
                loading = false
            }
        }
    }

    fun refresh() {
        job?.cancel()
        items.clear()
        seen.clear()
        cursor = ""
        done = false
        loading = false
        error = null
        loadMore()
    }

    fun remove(key: String) {
        items.removeAll { it.key == key }
    }
}

/** Feeds kept for the whole session, one per list on screen. */
object Feeds {
    private val feeds = HashMap<String, Feed>()

    fun all(filter: MediaFilter, query: String): Feed =
        feeds.getOrPut("all|$filter|$query") { Feed { Repo.searchAll(filter, query, it) } }

    fun chat(chatId: Long, filter: MediaFilter, query: String = ""): Feed =
        feeds.getOrPut("chat|$chatId|$filter|$query") { Feed { Repo.searchChat(chatId, filter, query, it) } }

    fun clear() = feeds.clear()

    /** Hidden chats changed: drop the lists across all chats so they load again without them. */
    fun clearAcrossChats() {
        feeds.keys.filter { it.startsWith("all|") }.forEach { feeds.remove(it) }
    }
}
