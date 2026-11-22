package com.cvlvky.cloudroll.ui

import androidx.compose.runtime.mutableStateListOf
import com.cvlvky.cloudroll.tg.MediaFilter
import com.cvlvky.cloudroll.tg.TgMedia
import java.util.concurrent.atomic.AtomicInteger

/** The screens above the home tabs. */
sealed interface Screen {
    val key: String

    data class ChatMedia(val chatId: Long, val start: MediaFilter = MediaFilter.PHOTOS) : Screen {
        override val key get() = "chat:$chatId"
    }

    data class Viewer(val listId: Int, val index: Int) : Screen {
        override val key get() = "viewer:$listId:$index"
    }

    data object Player : Screen {
        override val key get() = "player"
    }

    data object Hidden : Screen {
        override val key get() = "hidden"
    }

    data object Settings : Screen {
        override val key get() = "settings"
    }

    /** A film's page. [title] and [year] are only hints shown while TMDB answers. */
    data class Movie(val id: Int, val title: String = "", val year: Int = 0) : Screen {
        override val key get() = "movie:$id"
    }

    /** Telegram videos matching some text (series, or a film TMDB does not know). */
    data class TgSearch(val query: String) : Screen {
        override val key get() = "tgsearch:$query"
    }
}

/** A screen asked for from outside (a cloudroll:// link from ReelPulse), opened once signed in. */
object DeepLinks {
    val pending = androidx.compose.runtime.mutableStateOf<Screen?>(null)

    /** cloudroll://movie?tmdb=123&kind=movie&title=Leo&year=2023 */
    fun parse(uri: android.net.Uri?): Screen? {
        if (uri == null || uri.scheme != "cloudroll") return null
        val title = uri.getQueryParameter("title").orEmpty()
        val year = uri.getQueryParameter("year")?.toIntOrNull() ?: 0
        val id = uri.getQueryParameter("tmdb")?.toIntOrNull()
        val kind = uri.getQueryParameter("kind") ?: "movie"
        return when {
            id != null && kind == "movie" -> Screen.Movie(id, title, year)
            title.isNotBlank() -> Screen.TgSearch(title)
            else -> null
        }
    }
}

/** A plain back stack: the home tabs are always at the bottom. */
object Nav {
    val stack = mutableStateListOf<Screen>()

    fun go(s: Screen) {
        stack.add(s)
    }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun clear() = stack.clear()
}

/** Lists handed to the viewer, so it can swipe through the same items the screen showed. */
object ViewerLists {
    private val next = AtomicInteger(1)
    private val lists = HashMap<Int, List<TgMedia>>()

    fun put(list: List<TgMedia>): Int {
        val id = next.getAndIncrement()
        lists[id] = list
        if (lists.size > 20) lists.keys.sorted().take(lists.size - 20).forEach { lists.remove(it) }
        return id
    }

    fun get(id: Int): List<TgMedia> = lists[id].orEmpty()
}

fun openViewer(list: List<TgMedia>, index: Int) {
    val viewable = list.filter { it.isImage || it.isVideo }
    val item = list.getOrNull(index) ?: return
    val at = viewable.indexOfFirst { it.key == item.key }
    if (at < 0) return
    Nav.go(Screen.Viewer(ViewerLists.put(viewable), at))
}
