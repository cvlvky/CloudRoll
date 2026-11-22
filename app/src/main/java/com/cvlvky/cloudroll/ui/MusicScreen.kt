package com.cvlvky.cloudroll.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.cvlvky.cloudroll.data.Prefs
import com.cvlvky.cloudroll.player.Music
import com.cvlvky.cloudroll.tg.MediaFilter
import com.cvlvky.cloudroll.tg.Repo
import com.cvlvky.cloudroll.tg.TgMedia
import com.cvlvky.cloudroll.util.Fmt
import kotlinx.coroutines.delay

private enum class MusicView(val label: String) { SONGS("All songs"), FAVOURITES("Favourites"), VOICE("Voice messages") }

/** Every song and voice message from your chats, playable like a music app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen() {
    val ctx = LocalContext.current
    var viewName by rememberSaveable { mutableStateOf(MusicView.SONGS.name) }
    val view = MusicView.valueOf(viewName)
    var searching by rememberSaveable { mutableStateOf(false) }
    var queryInput by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val favourites by Prefs.favourites.flow.collectAsState()
    val now by Music.state.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (searching) {
                    OutlinedTextField(
                        value = queryInput,
                        onValueChange = { queryInput = it },
                        placeholder = { Text("Song, artist or file name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("Music")
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
                }
            },
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MusicView.entries.forEach { v ->
                FilterChip(selected = v == view, onClick = { viewName = v.name }, label = { Text(v.label) })
            }
        }
        when (view) {
            MusicView.FAVOURITES -> FavouritesList(favourites, now.key)
            else -> {
                val feed = remember(view, query) { Feeds.all(if (view == MusicView.VOICE) MediaFilter.VOICE else MediaFilter.MUSIC, query) }
                TrackList(feed, now.key, favourites)
            }
        }
    }
}

@Composable
private fun TrackList(feed: Feed, playingKey: String, favourites: Set<String>) {
    val ctx = LocalContext.current
    val state = rememberLazyListState()
    LaunchedEffect(feed) { if (feed.items.isEmpty()) feed.loadMore() }
    LaunchedEffect(feed, state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= state.layoutInfo.totalItemsCount - 10) feed.loadMore()
        }
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        if (feed.items.isNotEmpty()) {
            item {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { Music.play(ctx, feed.items.toList(), 0) }) {
                        SmallIcon(Icons.Filled.PlayArrow)
                        Text("  Play all")
                    }
                    OutlinedButton(onClick = { Music.play(ctx, feed.items.toList(), 0, shuffle = true) }) {
                        SmallIcon(Icons.Filled.Shuffle)
                        Text("  Shuffle")
                    }
                }
            }
        }
        itemsIndexed(feed.items, key = { _, m -> m.key }) { i, m ->
            TrackRow(m, playing = m.key == playingKey, favourite = m.key in favourites) {
                Music.play(ctx, feed.items.toList(), i)
            }
        }
        item {
            when {
                feed.loading -> Loading()
                feed.error != null -> EmptyState(Icons.Filled.Refresh, "Couldn't load", feed.error.orEmpty(), action = "Try again") { feed.loadMore() }
                feed.done && feed.items.isEmpty() -> EmptyState(
                    Icons.Filled.LibraryMusic,
                    "No music yet",
                    "Songs sent in your chats and channels show up here. Join a music channel in Telegram, or send songs to Saved Messages.",
                )
            }
        }
    }
}

@Composable
private fun TrackRow(m: TgMedia, playing: Boolean, favourite: Boolean, onClick: () -> Unit) {
    MediaRow(
        m,
        showChat = true,
        trailing = {
            IconButton(onClick = { Prefs.toggleFavourite(m.key) }) {
                Icon(
                    if (favourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (favourite) "Remove from favourites" else "Add to favourites",
                    tint = if (favourite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (playing) Icon(Icons.Filled.MusicNote, "Playing", tint = MaterialTheme.colorScheme.primary)
        },
        onClick = onClick,
    )
}

/** Favourites are kept as chat and message ids; their details are fetched from Telegram. */
@Composable
private fun FavouritesList(keys: Set<String>, playingKey: String) {
    val ctx = LocalContext.current
    val items = remember { mutableStateListOf<TgMedia>() }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(keys) {
        loading = true
        val loaded = keys.mapNotNull { k ->
            val parts = k.split(':')
            val chat = parts.getOrNull(0)?.toLongOrNull()
            val msg = parts.getOrNull(1)?.toLongOrNull()
            if (chat == null || msg == null) null else Repo.media(chat, msg)
        }.sortedByDescending { it.date }
        items.clear()
        items.addAll(loaded)
        loading = false
    }
    if (loading && items.isEmpty()) {
        Loading()
        return
    }
    if (items.isEmpty()) {
        EmptyState(Icons.Filled.FavoriteBorder, "No favourites", "Tap the heart on a song to keep it here.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Music.play(ctx, items.toList(), 0) }) {
                    SmallIcon(Icons.Filled.PlayArrow)
                    Text("  Play all")
                }
                OutlinedButton(onClick = { Music.play(ctx, items.toList(), 0, shuffle = true) }) {
                    SmallIcon(Icons.Filled.Shuffle)
                    Text("  Shuffle")
                }
            }
        }
        itemsIndexed(items, key = { _, m -> m.key }) { i, m ->
            TrackRow(m, playing = m.key == playingKey, favourite = true) { Music.play(ctx, items.toList(), i) }
        }
    }
}

/** The full music player. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen() {
    val now by Music.state.collectAsState()
    val favourites by Prefs.favourites.flow.collectAsState()
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var speedMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) {
                position = Music.position()
                duration = Music.duration()
            }
            delay(400)
        }
    }
    LaunchedEffect(now.active) { if (!now.active) Nav.back() }
    val cur = Music.current()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Now playing") },
            navigationIcon = { IconButton(onClick = { Nav.back() }) { Icon(Icons.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { Music.stop() }) { Icon(Icons.Filled.Close, "Stop and close") }
            },
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.82f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (cur?.thumb != null) {
                    TdImage(cur.thumb, cur.mini, Modifier.fillMaxSize(), target = 600)
                } else {
                    Icon(Icons.Filled.MusicNote, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                now.title.ifBlank { "Playing" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (now.artist.isNotBlank()) {
                Text(now.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(16.dp))
            Slider(
                value = if (dragging) dragValue else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    Music.seekTo((dragValue * duration).toLong())
                    position = (dragValue * duration).toLong()
                    dragging = false
                },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(Fmt.durationMs(if (dragging) (dragValue * duration).toLong() else position), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(Fmt.durationMs(duration), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { Music.toggleShuffle() }) {
                    Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (now.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { Music.previous() }) { Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(36.dp)) }
                FilledIconButton(
                    onClick = { Music.toggle() },
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) {
                    Icon(if (now.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (now.playing) "Pause" else "Play", Modifier.size(40.dp))
                }
                IconButton(onClick = { Music.next() }) { Icon(Icons.Filled.SkipNext, "Next", Modifier.size(36.dp)) }
                IconButton(onClick = { Music.cycleRepeat() }) {
                    Icon(
                        if (now.repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        "Repeat",
                        tint = if (now.repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val fav = now.key.isNotBlank() && now.key in favourites
                IconButton(onClick = { if (now.key.isNotBlank()) Prefs.toggleFavourite(now.key) }) {
                    Icon(
                        if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        "Favourite",
                        tint = if (fav) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    OutlinedButton(onClick = { speedMenu = true }) {
                        SmallIcon(Icons.Filled.Speed)
                        Text("  ${now.speed}×")
                    }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { s ->
                            DropdownMenuItem(text = { Text("$s×") }, onClick = {
                                Music.setSpeed(s)
                                speedMenu = false
                            })
                        }
                    }
                }
                Box {
                    val sleepLabel = when {
                        now.sleepAtEnd -> "End of track"
                        now.sleepAt > 0 -> "${((now.sleepAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1} min"
                        else -> "Sleep"
                    }
                    OutlinedButton(onClick = { sleepMenu = true }) {
                        SmallIcon(Icons.Filled.Bedtime)
                        Text("  $sleepLabel")
                    }
                    DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                        listOf(15, 30, 45, 60, 90).forEach { m ->
                            DropdownMenuItem(text = { Text("$m minutes") }, onClick = {
                                Music.sleep(m)
                                sleepMenu = false
                            })
                        }
                        DropdownMenuItem(text = { Text("End of this track") }, onClick = {
                            Music.sleep(0)
                            sleepMenu = false
                        })
                        if (now.sleepAt > 0 || now.sleepAtEnd) {
                            DropdownMenuItem(text = { Text("Turn off the sleep timer") }, onClick = {
                                Music.cancelSleep()
                                sleepMenu = false
                            })
                        }
                    }
                }
            }
            if (now.count > 1) {
                Spacer(Modifier.height(12.dp))
                Text("Track ${now.index + 1} of ${now.count}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
