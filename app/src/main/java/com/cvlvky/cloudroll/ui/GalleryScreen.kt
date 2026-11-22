package com.cvlvky.cloudroll.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cvlvky.cloudroll.tg.MediaFilter
import com.cvlvky.cloudroll.tg.Tg
import com.cvlvky.cloudroll.tg.TgMedia
import com.cvlvky.cloudroll.transfer.SaveEngine
import com.cvlvky.cloudroll.util.Fmt
import kotlinx.coroutines.launch

private val galleryFilters = listOf(MediaFilter.ALL, MediaFilter.PHOTOS, MediaFilter.VIDEOS, MediaFilter.FILES)

/** Every photo and video from all your chats, newest first, grouped by day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen() {
    var filterName by rememberSaveable { mutableStateOf(MediaFilter.ALL.name) }
    val filter = MediaFilter.valueOf(filterName)
    var searching by rememberSaveable { mutableStateOf(false) }
    var queryInput by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val feed = remember(filter, query) { Feeds.all(filter, query) }
    Column(Modifier.fillMaxSize()) {
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
                }
            },
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            galleryFilters.forEach { f ->
                FilterChip(selected = f == filter, onClick = { filterName = f.name }, label = { Text(f.label) })
            }
        }
        MediaGrid(
            feed = feed,
            empty = {
                EmptyState(
                    Icons.Filled.PhotoLibrary,
                    if (query.isBlank()) "Nothing here yet" else "Nothing found",
                    if (query.isBlank()) "Photos and videos from your chats and channels show up here, newest first." else "Try other words.",
                )
            },
        )
    }
}

/** A grid of photos and videos with a header for each day, loading more as it scrolls. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(feed: Feed, empty: @Composable () -> Unit, header: (@Composable () -> Unit)? = null, state: LazyGridState = rememberLazyGridState()) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(feed) { if (feed.items.isEmpty()) feed.loadMore() }
    LaunchedEffect(feed, state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= state.layoutInfo.totalItemsCount - 24) feed.loadMore()
        }
    }
    val rows by remember(feed) {
        derivedStateOf {
            val out = ArrayList<Any>()
            var lastDay = Long.MIN_VALUE
            feed.items.forEachIndexed { i, m ->
                val day = Fmt.dayKey(m.date)
                if (day != lastDay) {
                    out.add(Fmt.day(m.date))
                    lastDay = day
                }
                out.add(i)
            }
            out
        }
    }
    var menuFor by remember { mutableStateOf<TgMedia?>(null) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items(rows.size, key = { i -> rows[i].let { r -> if (r is Int) feed.items[r].key else "h:$r:$i" } }, span = { i ->
            if (rows[i] is String) GridItemSpan(maxLineSpan) else GridItemSpan(1)
        }) { i ->
            val r = rows[i]
            if (r is String) {
                Text(
                    r,
                    modifier = Modifier.padding(start = 10.dp, top = 14.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                val index = r as Int
                val m = feed.items[index]
                Box {
                    MediaTile(
                        m,
                        Modifier
                            .aspectRatio(1f)
                            .combinedClickable(
                                onClick = {
                                    if (m.isImage || m.isVideo) {
                                        openViewer(feed.items.toList(), index)
                                    } else {
                                        menuFor = m
                                    }
                                },
                                onLongClick = { menuFor = m },
                            ),
                    )
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
                        if (!m.isImage && !m.isVideo) {
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
