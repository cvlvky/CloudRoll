package com.cvlvky.cloudroll.movies

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Immutable
import com.cvlvky.cloudroll.BuildConfig
import com.cvlvky.cloudroll.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDate

class MovieKeyError(message: String) : Exception(message)

@Immutable
data class Movie(
    val id: Int,
    val title: String,
    val originalTitle: String,
    val year: Int?,
    val releaseDate: String,
    val poster: String?,
    val backdrop: String?,
    val rating: Double,
    val votes: Int,
    val language: String,
    val overview: String,
)

@Immutable
data class CastMember(val name: String, val character: String, val photo: String?)

@Immutable
data class Provider(val name: String, val logo: String?)

@Immutable
data class Providers(val link: String?, val stream: List<Provider>, val rent: List<Provider>, val buy: List<Provider>)

@Immutable
data class MovieDetails(
    val movie: Movie,
    val tagline: String,
    val runtime: Int,
    val genres: List<String>,
    val imdbId: String?,
    val cast: List<CastMember>,
    val directors: List<String>,
    val trailerKey: String?,
    val providers: Providers?,
    val similar: List<Movie>,
)

@Immutable
data class Ratings(val imdb: String?, val imdbVotes: String?, val rottenTomatoes: String?, val metacritic: String?, val awards: String?)

/** The languages the Movies tab can show, as TMDB names them. */
val MOVIE_LANGS = linkedMapOf(
    "ml" to "Malayalam",
    "ta" to "Tamil",
    "en" to "English",
    "hi" to "Hindi",
    "te" to "Telugu",
    "kn" to "Kannada",
    "ko" to "Korean",
)

val MOVIE_REGIONS = linkedMapOf("IN" to "India", "QA" to "Qatar", "AE" to "UAE", "US" to "USA", "GB" to "UK")

/**
 * TMDB (movies, posters, streaming) and OMDb (IMDb and Rotten Tomatoes scores), the same free
 * services ReelPulse uses. Answers are cached on the phone so lists open instantly.
 */
object Tmdb {
    private const val API = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p/"
    private lateinit var cacheDir: File
    private val memory = LruCache<String, String>(120)

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, "tmdb").apply { mkdirs() }
    }

    fun key(): String = Prefs.tmdbKey.value.trim().ifBlank { BuildConfig.TMDB_KEY.trim() }

    fun omdbKey(): String = Prefs.omdbKey.value.trim().ifBlank { BuildConfig.OMDB_KEY.trim() }

    fun hasKey(): Boolean = key().isNotBlank()

    fun image(path: String?, size: String = "w342"): String? = path?.takeIf { it.isNotBlank() }?.let { IMG + size + it }

    private fun hash(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** One TMDB request, from the cache when it is young enough. */
    private suspend fun get(path: String, params: Map<String, String>, ttlMs: Long): JSONObject = withContext(Dispatchers.IO) {
        val key = key()
        if (key.isBlank()) throw MovieKeyError("Add your TMDB key in Settings → Movies.")
        val bearer = key.startsWith("ey") && key.length > 60
        val all = linkedMapOf("language" to "en-US").apply { putAll(params) }
        val query = all.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val cacheKey = "$path?$query"
        memory.get(cacheKey)?.let { return@withContext JSONObject(it) }
        val file = File(cacheDir, hash(cacheKey) + ".json")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < ttlMs) {
            runCatching { file.readText() }.getOrNull()?.let {
                memory.put(cacheKey, it)
                return@withContext JSONObject(it)
            }
        }
        val url = "$API$path?$query" + if (bearer) "" else "&api_key=${enc(key)}"
        var attempt = 0
        while (true) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
                if (bearer) setRequestProperty("Authorization", "Bearer $key")
            }
            try {
                val code = c.responseCode
                if (code == 429 && attempt++ < 2) {
                    delay(1500L * attempt)
                    continue
                }
                if (code == 401) throw MovieKeyError("TMDB did not accept the key. Check it in Settings → Movies.")
                if (code != 200) {
                    // Offline or TMDB down: an old answer is better than none.
                    if (file.exists()) return@withContext JSONObject(file.readText())
                    throw IllegalStateException("TMDB answered $code")
                }
                val text = c.inputStream.bufferedReader().use { it.readText() }
                memory.put(cacheKey, text)
                runCatching { file.writeText(text) }
                return@withContext JSONObject(text)
            } catch (e: java.io.IOException) {
                if (file.exists()) return@withContext JSONObject(file.readText())
                throw IllegalStateException("No connection to TMDB")
            } finally {
                c.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        JSONObject()
    }

    private fun movieOf(o: JSONObject): Movie? {
        val id = o.optInt("id")
        if (id == 0) return null
        val date = o.optString("release_date")
        return Movie(
            id = id,
            title = o.optString("title").ifBlank { o.optString("original_title") },
            originalTitle = o.optString("original_title"),
            year = date.take(4).toIntOrNull(),
            releaseDate = date,
            poster = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" },
            backdrop = o.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" },
            rating = o.optDouble("vote_average", 0.0).let { if (it.isNaN()) 0.0 else it },
            votes = o.optInt("vote_count"),
            language = o.optString("original_language"),
            overview = o.optString("overview"),
        )
    }

    private fun moviesOf(arr: JSONArray?): List<Movie> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::movieOf) }
    }

    /**
     * The same discover query for each language, interleaved so Malayalam and Tamil are not
     * buried under Hollywood (as ReelPulse does it).
     */
    private suspend fun discover(langs: List<String>, params: Map<String, String>, ttlMs: Long, limit: Int = 24): List<Movie> = coroutineScope {
        val parts = langs.map { l ->
            async {
                runCatching {
                    moviesOf(get("/discover/movie", params + mapOf("include_adult" to "false", "with_original_language" to l), ttlMs).optJSONArray("results"))
                }
            }
        }.awaitAll()
        val firstError = parts.firstNotNullOfOrNull { it.exceptionOrNull() }
        val lists = parts.mapNotNull { it.getOrNull() }
        if (lists.isEmpty() && firstError != null) throw firstError
        val out = ArrayList<Movie>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) lists.forEach { l -> l.getOrNull(i)?.let(out::add) }
        out.distinctBy { it.id }.filter { it.poster != null }.take(limit)
    }

    enum class Row(val label: String) {
        THEATRES("Just released"),
        OTT("New on OTT"),
        TRENDING("Trending"),
        SOON("Coming soon"),
        TOP("Top rated"),
    }

    suspend fun row(row: Row, langs: List<String>, region: String): List<Movie> {
        val today = LocalDate.now()
        return when (row) {
            Row.THEATRES -> discover(
                langs,
                mapOf(
                    "primary_release_date.gte" to today.minusDays(45).toString(),
                    "primary_release_date.lte" to today.toString(),
                    "sort_by" to "primary_release_date.desc",
                    "with_release_type" to "2|3",
                ),
                3 * 3600_000L,
            )
            Row.OTT -> discover(
                langs,
                mapOf(
                    "watch_region" to region,
                    "with_watch_monetization_types" to "flatrate",
                    "primary_release_date.gte" to today.minusDays(240).toString(),
                    "primary_release_date.lte" to today.toString(),
                    "sort_by" to "primary_release_date.desc",
                ),
                3 * 3600_000L,
            )
            Row.TRENDING -> discover(
                langs,
                mapOf(
                    "primary_release_date.gte" to today.minusDays(150).toString(),
                    "primary_release_date.lte" to today.toString(),
                    "sort_by" to "popularity.desc",
                ),
                6 * 3600_000L,
            )
            Row.SOON -> discover(
                langs,
                mapOf(
                    "primary_release_date.gte" to today.plusDays(1).toString(),
                    "primary_release_date.lte" to today.plusDays(150).toString(),
                    "sort_by" to "primary_release_date.asc",
                ),
                12 * 3600_000L,
            )
            Row.TOP -> discover(
                langs,
                mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to "120"),
                3 * 24 * 3600_000L,
            )
        }
    }

    suspend fun search(query: String, year: Int? = null): List<Movie> {
        if (query.isBlank()) return emptyList()
        val params = linkedMapOf("query" to query, "include_adult" to "false")
        if (year != null) params["year"] = year.toString()
        return moviesOf(get("/search/movie", params, 7 * 24 * 3600_000L).optJSONArray("results"))
    }

    suspend fun details(id: Int, region: String): MovieDetails {
        val o = get(
            "/movie/$id",
            mapOf("append_to_response" to "credits,videos,watch/providers,recommendations,external_ids"),
            24 * 3600_000L,
        )
        val movie = movieOf(o) ?: throw IllegalStateException("Movie not found")
        val genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }.orEmpty()
        val credits = o.optJSONObject("credits")
        val cast = credits?.optJSONArray("cast")?.let { a ->
            (0 until minOf(a.length(), 20)).map { i ->
                val c = a.getJSONObject(i)
                CastMember(c.optString("name"), c.optString("character"), c.optString("profile_path").takeIf { it.isNotBlank() && it != "null" })
            }
        }.orEmpty()
        val directors = credits?.optJSONArray("crew")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("job") == "Director" }.map { it.optString("name") }
        }.orEmpty()
        val videos = o.optJSONObject("videos")?.optJSONArray("results")
        val trailer = videos?.let { a ->
            val all = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("site") == "YouTube" }
            (all.firstOrNull { it.optString("type") == "Trailer" && it.optBoolean("official") }
                ?: all.firstOrNull { it.optString("type") == "Trailer" }
                ?: all.firstOrNull { it.optString("type") == "Teaser" })?.optString("key")
        }
        val providers = o.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject(region)?.let { r ->
            fun list(k: String) = r.optJSONArray(k)?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map { Provider(it.optString("provider_name"), it.optString("logo_path").takeIf { p -> p.isNotBlank() }) }
            }.orEmpty()
            Providers(r.optString("link").takeIf { it.isNotBlank() }, list("flatrate") + list("free") + list("ads"), list("rent"), list("buy"))
        }
        return MovieDetails(
            movie = movie,
            tagline = o.optString("tagline"),
            runtime = o.optInt("runtime"),
            genres = genres,
            imdbId = o.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
                ?: o.optString("imdb_id").takeIf { it.startsWith("tt") },
            cast = cast,
            directors = directors,
            trailerKey = trailer,
            providers = providers,
            similar = moviesOf(o.optJSONObject("recommendations")?.optJSONArray("results")).filter { it.poster != null }.take(20),
        )
    }

    /** IMDb, Rotten Tomatoes and Metacritic from OMDb, when an OMDb key is set. */
    suspend fun ratings(imdbId: String): Ratings? = withContext(Dispatchers.IO) {
        val key = omdbKey()
        if (key.isBlank()) return@withContext null
        val cacheKey = "omdb:$imdbId"
        val file = File(cacheDir, hash(cacheKey) + ".json")
        val text = if (file.exists() && System.currentTimeMillis() - file.lastModified() < 3 * 24 * 3600_000L) {
            file.readText()
        } else {
            runCatching {
                val c = URL("https://www.omdbapi.com/?i=${enc(imdbId)}&apikey=${enc(key)}").openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 15_000
                try {
                    if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }.also { file.writeText(it) }
                } finally {
                    c.disconnect()
                }
            }.getOrNull() ?: return@withContext null
        }
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
        if (o.optString("Response") == "False") return@withContext null
        val list = o.optJSONArray("Ratings")
        fun source(name: String): String? = list?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("Source") == name }?.optString("Value")
        }
        Ratings(
            imdb = o.optString("imdbRating").takeIf { it.isNotBlank() && it != "N/A" },
            imdbVotes = o.optString("imdbVotes").takeIf { it.isNotBlank() && it != "N/A" },
            rottenTomatoes = source("Rotten Tomatoes"),
            metacritic = source("Metacritic"),
            awards = o.optString("Awards").takeIf { it.isNotBlank() && it != "N/A" },
        )
    }

    fun clearCache() {
        memory.evictAll()
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}
