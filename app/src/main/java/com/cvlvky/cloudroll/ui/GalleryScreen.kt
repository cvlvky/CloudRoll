package com.cvlvky.cloudroll.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.cvlvky.cloudroll.data.Prefs
import com.cvlvky.cloudroll.tg.MediaFilter
import com.cvlvky.cloudroll.tg.Page
import com.cvlvky.cloudroll.tg.Repo
import com.cvlvky.cloudroll.tg.Tg
import com.cvlvky.cloudroll.tg.TgMedia
import com.cvlvky.cloudroll.transfer.SaveEngine
import com.cvlvky.cloudroll.util.Fmt
import kotlinx.coroutines.launch
import java.io.File

private enum class GalleryView(val label: String, val filter: MediaFilter?) {
    ALL("All", MediaFilter.ALL),
    PHOTOS("Photos", MediaFilter.PHOTOS),
    VIDEOS("Videos", MediaFilter.VIDEOS),
    FILES("Files", MediaFilter.FILES),
    STARRED("Starred", null),
}

/** Every photo and video from all your chats, newest first, grouped by day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen() {
    var viewName by rememberSaveable { mutableStateOf(GalleryView.ALL.name) }
    val view = GalleryView.valueOf(viewName)
    var searching by rememberSaveable { mutableStateOf(false) }
    var queryInput by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val starred by Prefs.starred.flow.collectAsState()
    val selection = remember { mutableStateListOf<String>() }
    val feed = remember(view, query, if (view == GalleryView.STARRED) starred else null) {
        val f = view.filter
        if (f == null) starredFeed(starred) else Feeds.all(f, query)
    }
    Column(Modifier.fillMaxSize()) {
        if (selection.isNotEmpty()) {
            SelectionBar(feed, selection)
        } else {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = queryInput,
                            onValueChange = { queryInput = it },
                            placeholder = { Text("Search names and captions") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("Gallery")
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
                        IconButton(onClick = { feed.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                        SettingsAction()
                    }
                },
            )
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GalleryView.entries.forEach { v ->
                FilterChip(
                    selected = v == view,
                    onClick = {
                        viewName = v.name
                        selection.clear()
                    },
                    label = { Text(v.label) },
                    leadingIcon = if (v == GalleryView.STARRED) ({ Icon(Icons.Filled.Star, null, Modifier.size(16.dp)) }) else null,
                )
            }
        }
        MediaGrid(
            feed = feed,
            selection = selection,
            empty = {
                when {
                    view == GalleryView.STARRED -> EmptyState(Icons.Filled.StarBorder, "No starred photos", "Tap the star on a photo or video to keep it here.")
                    query.isBlank() -> EmptyState(Icons.Filled.PhotoLibrary, "Nothing here yet", "Photos and videos from your chats and channels show up here, newest first.")
                    else -> EmptyState(Icons.Filled.Search, "Nothing found", "Try other words.")
                }
            },
        )
    }
}

/** Starred items, read back from Telegram by their chat and message ids. */
private fun starredFeed(keys: Set<String>): Feed = Feed { _ ->
    val items = keys.mapNotNull { k ->
        val parts = k.split(':')
        val chat = parts.getOrNull(0)?.toLongOrNull()
        val msg = parts.getOrNull(1)?.toLongOrNull()
        if (chat == null || msg == null) null else Repo.media(chat, msg)
    }.sortedByDescending { it.date }
    Page(items, null)
}

/** The bar shown while items are selected: star, save, share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBar(feed: Feed, selection: SnapshotStateList<String>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val starred by Prefs.starred.flow.collectAsState()
    val chosen = feed.items.filter { it.key in selection }
    val allStarred = chosen.isNotEmpty() && chosen.all { it.key in starred }
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        navigationIcon = { IconButton(onClick = { selection.clear() }) { Icon(Icons.Filled.Close, "Clear selection") } },
        title = { Text("${selection.size} selected") },
        actions = {
            IconButton(onClick = {
                selection.clear()
                selection.addAll(feed.items.map { it.key })
            }) { Icon(Icons.Filled.SelectAll, "Select all") }
            IconButton(onClick = { Prefs.setStarred(chosen.map { it.key }, !allStarred) }) {
                Icon(if (allStarred) Icons.Filled.Star else Icons.Filled.StarBorder, if (allStarred) "Unstar" else "Star")
            }
            IconButton(onClick = {
                val ok = chosen.filter { it.canSave }
                chosen.groupBy { it.chatId }.forEach { (chatId, list) ->
                    SaveEngine.enqueue(ctx, list.filter { it.canSave }, Tg.chatTitle(chatId))
                }
                toast(ctx, if (ok.size == chosen.size) "Saving ${ok.size} to your phone" else "Saving ${ok.size}; ${chosen.size - ok.size} can't be saved")
                selection.clear()
            }) { Icon(Icons.Filled.Download, "Save to phone") }
            IconButton(onClick = {
                val list = chosen.filter { it.canSave }
                if (list.isEmpty()) {
                    toast(ctx, "These chats don't allow sharing")
                } else {
                    toast(ctx, "Getting ${list.size} files…")
                    scope.launch {
                        val paths = list.mapNotNull { m -> Tg.localPath(m.file, 16)?.let { it to m.mime } }
                        shareMany(ctx, paths)
                    }
                    selection.clear()
                }
            }) { Icon(Icons.Filled.Share, "Share") }
        },
    )
}

/** Shares several downloaded Telegram files at once. */
fun shareMany(ctx: Context, files: List<Pair<String, String>>) {
    if (files.isEmpty()) {
        toast(ctx, "Download failed")
        return
    }
    if (files.size == 1) {
        shareFile(ctx, files[0].first, files[0].second)
        return
    }
    try {
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(it.first)) })
        val types = files.map { it.second.substringBefore('/') }.distinct()
        val i = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType(if (types.size == 1) "${types[0]}/*" else "*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        toast(ctx, "Could not share these files")
    }
}

private data class DayHeader(val label: String, val day: Long)

/**
 * A grid of photos and videos with a header for each day. Pinch to change how many fit in a row;
 * press and hold to select. Loads more as it scrolls.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(
    feed: Feed,
    empty: @Composable () -> Unit,
    header: (@Composable () -> Unit)? = null,
    selection: SnapshotStateList<String>? = null,
    state: LazyGridState = rememberLazyGridState(),
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val columns by Prefs.gridColumns.flow.collectAsState()
    val starred by Prefs.starred.flow.collectAsState()
    LaunchedEffect(feed) { if (feed.items.isEmpty()) feed.loadMore() }
    LaunchedEffect(feed, state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= state.layoutInfo.totalItemsCount - 30) feed.loadMore()
        }
    }
    val rows by remember(feed) {
        derivedStateOf {
            val out = ArrayList<Any>()
            var lastDay = Long.MIN_VALUE
            feed.items.forEachIndexed { i, m ->
                val day = Fmt.dayKey(m.date)
                if (day != lastDay) {
                    out.add(DayHeader(Fmt.day(m.date), day))
                    lastDay = day
                }
                out.add(i)
            }
            out
        }
    }
    var menuFor by remember { mutableStateOf<TgMedia?>(null) }
    val selecting = selection != null && selection.isNotEmpty()
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Two fingers pinch the grid bigger or smaller; one finger still scrolls.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var zoom = 1f
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            zoom *= event.calculateZoom()
                            if (zoom > 1.3f) {
                                Prefs.gridColumns.value = (Prefs.gridColumns.value - 1).coerceAtLeast(2)
                                zoom = 1f
                            } else if (zoom < 0.77f) {
                                Prefs.gridColumns.value = (Prefs.gridColumns.value + 1).coerceAtMost(7)
                                zoom = 1f
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns.coerceIn(2, 7)),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(
                rows.size,
                key = { i -> rows[i].let { r -> if (r is DayHeader) "h:${r.day}" else feed.items[r as Int].key } },
                span = { i -> if (rows[i] is DayHeader) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            ) { i ->
                val r = rows[i]
                if (r is DayHeader) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = selection != null) {
                                // Tap a day to select (or unselect) all of it.
                                val keys = feed.items.filter { Fmt.dayKey(it.date) == r.day }.map { it.key }
                                if (selection != null) {
                                    if (keys.all { it in selection }) selection.removeAll(keys) else keys.forEach { if (it !in selection) selection.add(it) }
                                }
                            }
                            .padding(start = 10.dp, top = 14.dp, bottom = 6.dp, end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(r.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (selecting) Text("Select day", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    val index = r as Int
                    val m = feed.items[index]
                    val selected = selection != null && m.key in selection
                    Box {
                        MediaTile(
                            m,
                            Modifier
                                .aspectRatio(1f)
                                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier)
                                .combinedClickable(
                                    onClick = {
                                        when {
                                            selecting -> if (selected) selection?.remove(m.key) else selection?.add(m.key)
                                            m.isImage || m.isVideo -> openViewer(feed.items.toList(), index)
                                            else -> menuFor = m
                                        }
                                    },
                                    onLongClick = {
                                        if (selection != null) {
                                            if (selected) selection.remove(m.key) else selection.add(m.key)
                                        } else {
                                            menuFor = m
                                        }
                                    },
                                ),
                        )
                        if (m.key in starred) {
                            Icon(
                                Icons.Filled.Star,
                                "Starred",
                                tint = Color(0xFFFFC107),
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp),
                            )
                        }
                        if (selecting) {
                            Icon(
                                if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                null,
                                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                                    .size(22.dp)
                                    .background(if (selected) Color.White else Color.Black.copy(alpha = 0.25f), CircleShape),
                            )
                        }
                        DropdownMenu(expanded = menuFor?.key == m.key, onDismissRequest = { menuFor = null }) {
                            Text(
                                m.displayName,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${Tg.chatTitle(m.chatId)} · ${Fmt.size(m.size)}",
                                modifier = Modifier.padding(horizontal = 16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            DropdownMenuItem(text = { Text("Open the chat") }, onClick = {
                                menuFor = null
                                Nav.go(Screen.ChatMedia(m.chatId))
                            })
                            DropdownMenuItem(text = { Text(if (m.canSave) "Save to phone" else "Saving is turned off in this chat") }, enabled = m.canSave, onClick = {
                                menuFor = null
                                SaveEngine.enqueue(ctx, listOf(m), Tg.chatTitle(m.chatId))
                                toast(ctx, "Saving to your phone")
                            })
                            DropdownMenuItem(text = { Text("Open with another app") }, enabled = m.canSave, onClick = {
                                menuFor = null
                                scope.launch {
                                    toast(ctx, "Downloading…")
                                    val p = Tg.localPath(m.file, 16)
                                    if (p != null) openFile(ctx, p, m.mime) else toast(ctx, "Download failed")
                                }
                            })
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                when {
                    feed.loading -> Loading()
                    feed.error != null -> EmptyState(Icons.Filled.Refresh, "Couldn't load", feed.error.orEmpty(), action = "Try again") { feed.loadMore() }
                    feed.done && feed.items.isEmpty() -> empty()
                }
            }
        }
    }
}
