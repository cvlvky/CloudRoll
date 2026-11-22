package com.cvlvky.cloudroll.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvlvky.cloudroll.data.Prefs
import com.cvlvky.cloudroll.movies.MOVIE_LANGS
import com.cvlvky.cloudroll.movies.Movie
import com.cvlvky.cloudroll.movies.ReelPulse
import com.cvlvky.cloudroll.movies.Resume
import com.cvlvky.cloudroll.movies.TgMovies
import com.cvlvky.cloudroll.movies.Tmdb
import com.cvlvky.cloudroll.util.Fmt
import kotlinx.coroutines.launch
import java.util.Locale

/** The gear on every tab's bar. */
@Composable
fun SettingsAction() {
    IconButton(onClick = { Nav.go(Screen.Settings) }) { Icon(Icons.Filled.Settings, "Settings") }
}

/** Movie lists kept for the session, so coming back to the tab is instant. */
object MovieRows {
    private val cache = HashMap<String, List<Movie>>()
    fun get(key: String) = cache[key]
    fun put(key: String, list: List<Movie>) {
        cache[key] = list
    }
    fun clear() = cache.clear()
}

/**
 * Movies: ReelPulse's discovery (new releases, OTT, trending, coming soon, top rated) joined with
 * your Telegram: films posted in your chats, and the ones you stopped half way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoviesScreen() {
    var searching by rememberSaveable { mutableStateOf(false) }
    var queryInput by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val tmdbKey by Prefs.tmdbKey.flow.collectAsState()
    val langsSet by Prefs.movieLangs.flow.collectAsState()
    val region by Prefs.movieRegion.flow.collectAsState()
    val hasKey = remember(tmdbKey) { Tmdb.hasKey() }
    val langs = remember(langsSet) { Prefs.langs() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (searching) {
                    OutlinedTextField(
                        value = queryInput,
                        onValueChange = { queryInput = it },
                        placeholder = { Text("Search films") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { query = queryInput.trim() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("Movies")
                }
            },
            actions = {
                if (searching) {
                    IconButton(onClick = { query = queryInput.trim() }) { Icon(Icons.Filled.Search, "Search") }
                    IconButton(onClick = {
                        searching = false
                        queryInput = ""
                        query = ""
                    }) { Icon(Icons.Filled.Close, "Close search") }
                } else {
                    IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search") }
                    IconButton(onClick = {
                        MovieRows.clear()
                        refresh++
                    }) { Icon(Icons.Filled.Refresh, "Refresh") }
                    SettingsAction()
                }
            },
        )
        if (searching && query.isNotBlank()) {
            MovieSearch(query, hasKey)
        } else {
            MoviesHome(hasKey, langs, region, refresh)
        }
    }
}

@Composable
private fun MoviesHome(hasKey: Boolean, langs: List<String>, region: String, refresh: Int) {
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "langs") { LanguageChips(langs) }
        if (!hasKey) item(key = "key") { TmdbKeyCard() }
        item(key = "continue") { ContinueWatchingShelf(refresh) }
        if (hasKey) item(key = "telegram") { TelegramMoviesShelf(refresh) }
        if (hasKey) {
            Tmdb.Row.entries.forEach { r ->
                item(key = "row:${r.name}") { TmdbShelf(r, langs, region, refresh) }
            }
        }
        item(key = "reelpulse") {
            Card(
                Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Your diary is in ReelPulse", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Watchlist, ratings, reviews and OTT alerts stay in ReelPulse. Every film page here has an Open in ReelPulse button, " +
                            "and ReelPulse's film pages have Watch from Telegram, which opens the film here.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (ReelPulse.installed(ctx)) {
                        OutlinedButton(onClick = { ReelPulse.launch(ctx) }) {
                            SmallIcon(Icons.Filled.OpenInNew)
                            Text("  Open ReelPulse")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageChips(langs: List<String>) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MOVIE_LANGS.forEach { (code, name) ->
            val on = code in langs
            FilterChip(
                selected = on,
                onClick = {
                    val now = langs.toMutableSet()
                    if (on) {
                        if (now.size > 1) now.remove(code)
                    } else {
                        now.add(code)
                    }
                    Prefs.movieLangs.value = now
                },
                label = { Text(name) },
            )
        }
    }
}

@Composable
private fun TmdbKeyCard() {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add your TMDB key for posters, new releases and OTT", fontWeight = FontWeight.SemiBold)
            Text(
                "Use the same free key as ReelPulse. Or get one at themoviedb.org: Settings → API → API Key. " +
                    "Without it, Continue watching and Telegram search still work.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text("TMDB API key or read token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (key.length >= 16) Prefs.tmdbKey.value = key else toast(ctx, "That key looks too short") }) { Text("Save") }
                TextButton(onClick = { openUrl(ctx, "https://www.themoviedb.org/settings/api") }) { Text("Get a key") }
            }
        }
    }
}

/** A row heading and a horizontal list. */
@Composable
fun Shelf(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }
        Box(Modifier.padding(top = 8.dp)) { content() }
    }
}

/** A film poster with its title, year and score. */
@Composable
fun PosterCard(
    title: String,
    year: Int?,
    poster: String?,
    rating: Double,
    badge: String? = null,
    width: androidx.compose.ui.unit.Dp = 112.dp,
    onClick: () -> Unit,
) {
    Column(Modifier.width(width).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))) {
            NetImage(poster, Modifier.fillMaxSize())
            if (poster == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (rating > 0) {
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(12.dp))
                    Text(String.format(Locale.US, "%.1f", rating), color = Color.White, fontSize = 11.sp)
                }
            }
            if (badge != null) {
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
        if (year != null && year > 0) Text("$year", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TmdbShelf(row: Tmdb.Row, langs: List<String>, region: String, refresh: Int) {
    val key = "${row.name}|${langs.sorted().joinToString(",")}|$region"
    var items by remember(key, refresh) { mutableStateOf(MovieRows.get(key)) }
    var error by remember(key, refresh) { mutableStateOf<String?>(null) }
    LaunchedEffect(key, refresh) {
        if (items == null) {
            try {
                val list = Tmdb.row(row, langs, region)
                MovieRows.put(key, list)
                items = list
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load"
            }
        }
    }
    Shelf(row.label) {
        val list = items
        when {
            error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            list == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            list.isEmpty() -> Text("Nothing here for these languages.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            else -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { m ->
                    val badge = when (row) {
                        Tmdb.Row.SOON -> m.releaseDate.takeIf { it.isNotBlank() }?.let { shortDate(it) }
                        else -> null
                    }
                    PosterCard(m.title, m.year, Tmdb.image(m.poster), m.rating, badge) { Nav.go(Screen.Movie(m.id, m.title, m.year ?: 0)) }
                }
            }
        }
    }
}

private fun shortDate(iso: String): String = try {
    java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
} catch (e: Exception) {
    iso
}

@Composable
private fun TelegramMoviesShelf(refresh: Int) {
    val library by TgMovies.library.collectAsState()
    val scan by TgMovies.scan.collectAsState()
    val find by Prefs.findMovies.flow.collectAsState()
    LaunchedEffect(refresh, find) { if (find) TgMovies.scanLibrary(force = refresh > 0) }
    if (!find) return
    val sub = when {
        scan.running && scan.total > 0 -> "Matching ${scan.checked} of ${scan.total} videos from your chats…"
        scan.running -> "Reading the videos in your chats…"
        library.isEmpty() && scan.done -> "No films found in your recent chat videos yet."
        else -> "${library.size} films in your chats"
    }
    Shelf("In your Telegram", sub) {
        if (library.isEmpty()) {
            if (scan.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            return@Shelf
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(library, key = { it.tmdbId }) { m ->
                val best = m.copies.firstOrNull()
                val badge = listOfNotNull(best?.quality, if (m.copies.size > 1) "${m.copies.size} copies" else null).joinToString(" · ").ifBlank { null }
                PosterCard(m.title, m.year, Tmdb.image(m.poster), 0.0, badge) { Nav.go(Screen.Movie(m.tmdbId, m.title, m.year)) }
            }
        }
    }
}

@Composable
private fun ContinueWatchingShelf(refresh: Int) {
    var list by remember { mutableStateOf<List<Resume>?>(null) }
    val depth = Nav.stack.size
    LaunchedEffect(refresh, depth) { if (depth == 0) list = TgMovies.continueWatching() }
    val items = list
    if (items.isNullOrEmpty()) return
    Shelf("Continue watching") {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.position.key }) { r ->
                Column(Modifier.width(168.dp).clickable { openViewer(listOf(r.media), 0) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
                        if (r.poster != null) {
                            NetImage(Tmdb.image(r.poster, "w342"), Modifier.fillMaxSize())
                        } else {
                            TdImage(r.media.thumb, r.media.mini, Modifier.fillMaxSize())
                        }
                        val d = r.position.duration
                        if (d > 0) {
                            LinearProgressIndicator(
                                progress = { (r.position.position.toFloat() / d).coerceIn(0f, 1f) },
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                            )
                        }
                    }
                    Text(
                        r.position.title.ifBlank { r.media.displayName },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "${Fmt.durationMs(r.position.position)} of ${Fmt.durationMs(r.position.duration)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MovieSearch(query: String, hasKey: Boolean) {
    var results by remember(query) { mutableStateOf<List<Movie>?>(null) }
    var error by remember(query) { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        if (!hasKey) return@LaunchedEffect
        try {
            results = Tmdb.search(query)
        } catch (e: Exception) {
            error = e.message
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedButton(onClick = { Nav.go(Screen.TgSearch(query)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            SmallIcon(Icons.Filled.Send)
            Text("  Search your Telegram for \"$query\"")
        }
        val list = results
        when {
            !hasKey -> EmptyState(Icons.Filled.Movie, "Add a TMDB key", "Film search needs a TMDB key. Telegram search works without one.")
            error != null -> EmptyState(Icons.Filled.Refresh, "Couldn't search", error.orEmpty())
            list == null -> Loading()
            list.isEmpty() -> EmptyState(Icons.Filled.Movie, "No films found", "Try the English title, or search your Telegram.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(list, key = { it.id }) { m ->
                    PosterCard(m.title, m.year, Tmdb.image(m.poster), m.rating, width = 110.dp) { Nav.go(Screen.Movie(m.id, m.title, m.year ?: 0)) }
                }
            }
        }
    }
}
