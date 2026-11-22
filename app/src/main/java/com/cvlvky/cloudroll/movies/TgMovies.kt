package com.cvlvky.cloudroll.movies

import androidx.compose.runtime.Immutable
import com.cvlvky.cloudroll.data.Db
import com.cvlvky.cloudroll.data.Match
import com.cvlvky.cloudroll.data.Position
import com.cvlvky.cloudroll.data.Prefs
import com.cvlvky.cloudroll.tg.MediaFilter
import com.cvlvky.cloudroll.tg.Repo
import com.cvlvky.cloudroll.tg.TgMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** A copy of a film in a Telegram chat, with what its name says about quality. */
@Immutable
data class TgCopy(val media: TgMedia, val parsed: ParsedName?) {
    val quality: String? get() = parsed?.quality
    val label: String
        get() = listOfNotNull(
            parsed?.quality,
            if (media.fileName.contains("hevc", true) || media.fileName.contains("x265", true)) "HEVC" else null,
        ).joinToString(" ")
}

/** A film found in your chats: TMDB's details and every copy posted. */
@Immutable
data class TgMovie(val tmdbId: Int, val title: String, val year: Int, val poster: String?, val copies: List<TgCopy>, val latest: Int)

/** A part-watched Telegram video for Continue watching. */
@Immutable
data class Resume(val position: Position, val media: TgMedia, val poster: String?)

/**
 * The bridge between Telegram and the movie world: which videos in your chats are which films,
 * and where a given film is in your chats.
 */
object TgMovies {
    data class ScanState(val running: Boolean = false, val checked: Int = 0, val total: Int = 0, val done: Boolean = false, val error: String? = null)

    val scan = MutableStateFlow(ScanState())
    val library = MutableStateFlow<List<TgMovie>>(emptyList())
    private val mutex = Mutex()
    private val inFlight = Semaphore(4)

    /** Looks a cleaned file name up on TMDB once, then remembers the answer. */
    suspend fun match(p: ParsedName): Match? {
        if (p.isEpisode || !Tmdb.hasKey()) return null
        Db.match(p.key)?.let { m ->
            // "No match" is checked again after a week: TMDB adds new films every day.
            if (m.tmdbId != 0 || System.currentTimeMillis() - m.checkedAt < 7 * 24 * 3600_000L) return m.takeIf { it.tmdbId != 0 }
        }
        val found = inFlight.withPermit {
            runCatching { Tmdb.search(p.title, p.year).ifEmpty { if (p.year != null) Tmdb.search(p.title) else emptyList() } }.getOrNull()
        } ?: return null
        val best = found
            .map { m -> m to maxOf(TitleParser.similarity(p.title, m.title), TitleParser.similarity(p.title, m.originalTitle)) }
            .filter { (m, score) ->
                val my = m.year
                val py = p.year
                score >= 0.8 && (py == null || my == null || kotlin.math.abs(my - py) <= 1)
            }
            .maxByOrNull { (m, score) -> score * 10 + (if (p.year != null && m.year == p.year) 2.0 else 0.0) + kotlin.math.ln(1.0 + m.votes) / 5 }
            ?.first
        val match = Match(p.key, best?.id ?: 0, best?.title.orEmpty(), best?.year ?: 0, best?.poster.orEmpty(), System.currentTimeMillis())
        Db.saveMatch(match)
        return match.takeIf { it.tmdbId != 0 }
    }

    /** The film a Telegram video is, if its name or caption says. */
    suspend fun matchFor(m: TgMedia): Match? {
        val p = parsedOf(m) ?: return null
        return withContext(Dispatchers.IO) { match(p) }
    }

    fun parsedOf(m: TgMedia): ParsedName? =
        TitleParser.parse(m.fileName) ?: TitleParser.parse(m.caption)

    private fun isFilm(m: TgMedia): Boolean =
        m.isVideo && m.kind != com.cvlvky.cloudroll.tg.Kind.GIF && (m.duration == 0 || m.duration >= 40 * 60 || m.size >= 300L * 1024 * 1024)

    /** Reads the newest videos in your chats and finds which films they are. */
    suspend fun scanLibrary(force: Boolean = false) {
        if (!Prefs.findMovies.value || !Tmdb.hasKey()) return
        if (!mutex.tryLock()) return
        try {
            if (!force && scan.value.done && library.value.isNotEmpty()) return
            scan.value = ScanState(running = true)
            val videos = ArrayList<TgMedia>()
            for (filter in listOf(MediaFilter.VIDEOS, MediaFilter.FILES)) {
                var cursor = ""
                for (i in 0 until 6) {
                    val page = runCatching { Repo.searchAll(filter, "", cursor) }.getOrNull() ?: break
                    videos += page.items.filter(::isFilm)
                    cursor = page.next ?: break
                }
            }
            val unique = videos.distinctBy { it.key }
            val parsed = unique.mapNotNull { m -> parsedOf(m)?.takeIf { !it.isEpisode }?.let { m to it } }
            scan.value = ScanState(running = true, total = parsed.size)
            var checked = 0
            val matched = coroutineScope {
                parsed.map { (m, p) ->
                    async {
                        val match = match(p)
                        synchronized(this@TgMovies) {
                            checked++
                            scan.value = scan.value.copy(checked = checked)
                        }
                        match?.let { Triple(it, m, p) }
                    }
                }.awaitAll().filterNotNull()
            }
            library.value = matched.groupBy { it.first.tmdbId }.map { (id, list) ->
                val first = list.first().first
                TgMovie(
                    tmdbId = id,
                    title = first.title,
                    year = first.year,
                    poster = first.poster.ifBlank { null },
                    copies = list.map { TgCopy(it.second, it.third) }
                        .sortedWith(compareByDescending<TgCopy> { TitleParser.qualityRank(it.quality) }.thenByDescending { it.media.size }),
                    latest = list.maxOf { it.second.date },
                )
            }.sortedByDescending { it.latest }
            scan.value = ScanState(done = true, checked = parsed.size, total = parsed.size)
        } catch (e: Exception) {
            scan.value = ScanState(done = true, error = e.message)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Every copy of a film in your chats: searched by its title (and original title), then kept
     * only when the cleaned name is the same film.
     */
    suspend fun findCopies(title: String, originalTitle: String?, year: Int?, tmdbId: Int?): List<TgCopy> = coroutineScope {
        val queries = listOfNotNull(title, originalTitle?.takeIf { it.isNotBlank() && TitleParser.norm(it) != TitleParser.norm(title) })
        val results = queries.flatMap { q ->
            listOf(MediaFilter.VIDEOS, MediaFilter.FILES).map { f ->
                async { runCatching { Repo.searchAll(f, q, "").items }.getOrDefault(emptyList()) }
            }
        }.awaitAll().flatten().distinctBy { it.key }
        val fromLibrary = library.value.firstOrNull { it.tmdbId == tmdbId }?.copies.orEmpty()
        val found = results.filter { it.isVideo }.mapNotNull { m ->
            val p = parsedOf(m)
            val score = p?.let { maxOf(TitleParser.similarity(it.title, title), originalTitle?.let { o -> TitleParser.similarity(it.title, o) } ?: 0.0) } ?: 0.0
            val py = p?.year
            val yearOk = year == null || py == null || kotlin.math.abs(py - year) <= 1
            if (score >= 0.8 && yearOk) TgCopy(m, p) else null
        }
        (fromLibrary + found).distinctBy { it.media.key }
            .sortedWith(compareByDescending<TgCopy> { TitleParser.qualityRank(it.quality) }.thenByDescending { it.media.size })
    }

    /** Telegram videos matching free text, for series and anything TMDB does not know. */
    suspend fun searchVideos(query: String): List<TgCopy> = coroutineScope {
        listOf(MediaFilter.VIDEOS, MediaFilter.FILES).map { f ->
            async { runCatching { Repo.searchAll(f, query, "").items }.getOrDefault(emptyList()) }
        }.awaitAll().flatten().distinctBy { it.key }.filter { it.isVideo }
            .map { TgCopy(it, parsedOf(it)) }
            .sortedWith(compareBy<TgCopy> { it.parsed?.season ?: 0 }.thenBy { it.parsed?.episode ?: 0 }.thenByDescending { it.media.date })
    }

    private val resumeCache = HashMap<String, TgMedia>()

    /** Part-watched videos, newest first, with a poster when the film is known. */
    suspend fun continueWatching(): List<Resume> {
        val positions = withContext(Dispatchers.IO) { Db.continueWatching() }
        return positions.mapNotNull { pos ->
            val media = resumeCache[pos.key] ?: Repo.media(pos.chatId, pos.messageId)?.also { resumeCache[pos.key] = it }
            if (media == null) {
                withContext(Dispatchers.IO) { Db.removePosition(pos.key) }
                null
            } else {
                val poster = parsedOf(media)?.let { p -> withContext(Dispatchers.IO) { Db.match(p.key) } }?.poster?.ifBlank { null }
                Resume(pos, media, poster)
            }
        }
    }
}
