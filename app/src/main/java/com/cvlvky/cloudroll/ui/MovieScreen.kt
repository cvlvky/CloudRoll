package com.cvlvky.cloudroll.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cvlvky.cloudroll.data.Prefs
import com.cvlvky.cloudroll.movies.MovieDetails
import com.cvlvky.cloudroll.movies.Provider
import com.cvlvky.cloudroll.movies.Ratings
import com.cvlvky.cloudroll.movies.ReelPulse
import com.cvlvky.cloudroll.movies.TgCopy
import com.cvlvky.cloudroll.movies.TgMovies
import com.cvlvky.cloudroll.movies.Tmdb
import com.cvlvky.cloudroll.tg.Tg
import com.cvlvky.cloudroll.transfer.SaveEngine
import com.cvlvky.cloudroll.util.Fmt
import java.util.Locale

/** A film: TMDB's details, ratings, where it streams, and every copy in your Telegram. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieScreen(id: Int, hintTitle: String, hintYear: Int) {
    val ctx = LocalContext.current
    val region by Prefs.movieRegion.flow.collectAsState()
    var details by remember(id) { mutableStateOf<MovieDetails?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var ratings by remember(id) { mutableStateOf<Ratings?>(null) }
    var copies by remember(id) { mutableStateOf<List<TgCopy>?>(null) }
    var expanded by remember(id) { mutableStateOf(false) }

    LaunchedEffect(id, region) {
        val d = try {
            Tmdb.details(id, region)
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this film"
            null
        }
        details = d
        val title = d?.movie?.title ?: hintTitle
        if (title.isNotBlank()) {
            copies = TgMovies.findCopies(title, d?.movie?.originalTitle, d?.movie?.year ?: hintYear.takeIf { it > 0 }, id)
        } else {
            copies = emptyList()
        }
        d?.imdbId?.let { ratings = Tmdb.ratings(it) }
    }

    val d = details
    val title = d?.movie?.title ?: hintTitle.ifBlank { "Film" }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = { Nav.back() }) { Icon(Icons.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = {
                    val text = "$title${d?.movie?.year?.let { " ($it)" } ?: ""}\n${ReelPulse.tmdbLink(id)}"
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))
                }) { Icon(Icons.Filled.Share, "Share") }
            },
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "head") { Header(d, title, hintYear) }
            if (error != null && d == null) {
                item { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            }
            item(key = "scores") { Scores(d, ratings) }
            item(key = "actions") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val best = copies?.firstOrNull()
                    Button(onClick = {
                        if (best != null) openViewer(listOf(best.media), 0) else toast(ctx, "No copy found in your Telegram")
                    }, enabled = best != null) {
                        SmallIcon(Icons.Filled.PlayArrow)
                        Text(if (best != null) "  Play from Telegram" else "  Not in Telegram")
                    }
                    d?.trailerKey?.let { key ->
                        OutlinedButton(onClick = { openUrl(ctx, "https://www.youtube.com/watch?v=$key") }) {
                            SmallIcon(Icons.Filled.Theaters)
                            Text("  Trailer")
                        }
                    }
                    OutlinedButton(onClick = { ReelPulse.open(ctx, id) }) {
                        SmallIcon(Icons.Filled.MenuBook)
                        Text(if (ReelPulse.installed(ctx)) "  Open in ReelPulse" else "  TMDB page")
                    }
                }
            }
            if (d != null && d.movie.overview.isNotBlank()) {
                item(key = "overview") {
                    Text(
                        d.movie.overview,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).clickable { expanded = !expanded },
                    )
                }
            }
            item(key = "copies") { Copies(copies, title) }
            d?.providers?.let { p ->
                item(key = "providers") {
                    Column(Modifier.padding(top = 8.dp)) {
                        SectionLabel("Where to watch in ${com.cvlvky.cloudroll.movies.MOVIE_REGIONS[region] ?: region}")
                        if (p.stream.isEmpty() && p.rent.isEmpty() && p.buy.isEmpty()) {
                            Text("Not on a streaming service here yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        ProviderRow("Stream", p.stream, p.link)
                        ProviderRow("Rent", p.rent, p.link)
                        ProviderRow("Buy", p.buy, p.link)
                    }
                }
            }
            if (d != null && d.cast.isNotEmpty()) {
                item(key = "cast") {
                    Shelf("Cast", d.directors.takeIf { it.isNotEmpty() }?.let { "Directed by ${it.joinToString(", ")}" }) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(d.cast, key = { it.name + it.character }) { c ->
                                Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    NetImage(Tmdb.image(c.photo, "w185"), Modifier.size(68.dp).clip(CircleShape))
                                    Text(c.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                                    if (c.character.isNotBlank()) {
                                        Text(c.character, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (d != null && d.similar.isNotEmpty()) {
                item(key = "similar") {
                    Shelf("More like this") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(d.similar, key = { it.id }) { m ->
                                PosterCard(m.title, m.year, Tmdb.image(m.poster), m.rating) { Nav.go(Screen.Movie(m.id, m.title, m.year ?: 0)) }
                            }
                        }
                    }
                }
            }
            item(key = "credit") {
                Text(
                    "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(d: MovieDetails?, title: String, hintYear: Int) {
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            NetImage(Tmdb.image(d?.movie?.backdrop, "w780"), Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp)
                .offset(y = 120.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.width(104.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))) {
                NetImage(Tmdb.image(d?.movie?.poster), Modifier.fillMaxSize())
                if (d?.movie?.poster == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Movie, null) }
                }
            }
            Column(Modifier.padding(start = 12.dp, bottom = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val m = d?.movie
                if (m != null && m.originalTitle.isNotBlank() && TitleNorm.differs(m.originalTitle, m.title)) {
                    Text(m.originalTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val facts = listOfNotNull(
                    (m?.year ?: hintYear.takeIf { it > 0 })?.toString(),
                    d?.runtime?.takeIf { it > 0 }?.let { "${it / 60}h ${it % 60}m" },
                    m?.language?.let { com.cvlvky.cloudroll.movies.MOVIE_LANGS[it] ?: it.uppercase(Locale.ROOT) },
                ) + d?.genres.orEmpty().take(3)
                Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!d?.tagline.isNullOrBlank()) {
                    Text(d?.tagline.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
        }
    }
    Spacer(Modifier.height(124.dp))
}

private object TitleNorm {
    fun differs(a: String, b: String) = com.cvlvky.cloudroll.movies.TitleParser.norm(a) != com.cvlvky.cloudroll.movies.TitleParser.norm(b)
}

@Composable
private fun Scores(d: MovieDetails?, r: Ratings?) {
    if (d == null) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (d.movie.votes > 0) Score(String.format(Locale.US, "%.1f", d.movie.rating), "TMDB · ${d.movie.votes} votes")
        if (r != null) {
            val imdb = r.imdb
            val votes = r.imdbVotes
            if (imdb != null) Score(imdb, if (votes != null) "IMDb · $votes" else "IMDb")
            r.rottenTomatoes?.let { Score(it, "Rotten Tomatoes") }
            r.metacritic?.let { Score(it, "Metacritic") }
        }
    }
    val awards = r?.awards
    if (awards != null) {
        Text("🏆 $awards", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
    }
}

@Composable
private fun Score(value: String, label: String) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = Color(0xFFFFC107))
            Text(" $value", fontWeight = FontWeight.Bold)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Copies(copies: List<TgCopy>?, title: String) {
    val ctx = LocalContext.current
    Column(Modifier.padding(top = 8.dp)) {
        SectionLabel("In your Telegram")
        when {
            copies == null -> Loading()
            copies.isEmpty() -> {
                Text(
                    "No copy of this film was found in your chats and channels.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                TextButton(onClick = { Nav.go(Screen.TgSearch(title)) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    SmallIcon(Icons.Filled.Search)
                    Text("  Search Telegram for \"$title\"")
                }
            }
            else -> {
                val medias = copies.map { it.media }
                copies.forEachIndexed { i, c ->
                    MediaRow(
                        c.media,
                        showChat = true,
                        trailing = {
                            if (c.label.isNotBlank()) AssistChip(onClick = {}, label = { Text(c.label) })
                            IconButton(onClick = {
                                if (c.media.canSave) {
                                    SaveEngine.enqueue(ctx, listOf(c.media), Tg.chatTitle(c.media.chatId))
                                    toast(ctx, "Saving to your phone")
                                } else {
                                    toast(ctx, "This chat does not allow saving")
                                }
                            }) { Icon(Icons.Filled.Download, "Save to phone") }
                        },
                    ) { openViewer(medias, i) }
                    HorizontalDivider(Modifier.padding(start = 72.dp))
                }
                TextButton(onClick = { Nav.go(Screen.TgSearch(title)) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Search Telegram for more")
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(label: String, list: List<Provider>, link: String?) {
    if (list.isEmpty()) return
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(52.dp))
        list.distinctBy { it.name }.forEach { p ->
            Column(
                Modifier.width(64.dp).clickable { if (link != null) openUrl(ctx, link) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NetImage(Tmdb.image(p.logo, "w92"), Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
                Text(p.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Telegram videos matching some text: series, or anything TMDB does not know. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TgSearchScreen(start: String) {
    var input by remember(start) { mutableStateOf(start) }
    var query by remember(start) { mutableStateOf(start) }
    var results by remember(query) { mutableStateOf<List<TgCopy>?>(null) }
    LaunchedEffect(query) { results = TgMovies.searchVideos(query) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                androidx.compose.material3.OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    placeholder = { Text("Search videos in your chats") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            navigationIcon = { IconButton(onClick = { Nav.back() }) { Icon(Icons.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { query = input.trim() }) { Icon(Icons.Filled.Search, "Search") } },
        )
        val list = results
        when {
            list == null -> Loading()
            list.isEmpty() -> EmptyState(Icons.Filled.Movie, "Nothing found", "No video in your chats matches \"$query\". Try a shorter name.")
            else -> {
                val medias = list.map { it.media }
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            "${list.size} videos in your chats",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    items(list.size, key = { list[it].media.key }) { i ->
                        val c = list[i]
                        val ep = c.parsed?.let { p ->
                            if (p.isEpisode) listOfNotNull(p.season?.let { "S$it" }, p.episode?.let { "E$it" }).joinToString("") else null
                        }
                        MediaRow(
                            c.media,
                            showChat = true,
                            trailing = {
                                val label = listOfNotNull(ep, c.label.ifBlank { null }).joinToString(" · ")
                                if (label.isNotBlank()) AssistChip(onClick = {}, label = { Text(label) })
                            },
                        ) { openViewer(medias, i) }
                    }
                }
            }
        }
    }
}
