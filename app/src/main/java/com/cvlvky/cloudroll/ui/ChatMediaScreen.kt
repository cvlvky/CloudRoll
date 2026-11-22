package com.cvlvky.cloudroll.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cvlvky.cloudroll.player.Music
import com.cvlvky.cloudroll.tg.ChatKind
import com.cvlvky.cloudroll.tg.MediaFilter
import com.cvlvky.cloudroll.tg.Repo
import com.cvlvky.cloudroll.tg.Sponsored
import com.cvlvky.cloudroll.tg.Tg
import com.cvlvky.cloudroll.tg.TgMedia
import com.cvlvky.cloudroll.transfer.SaveEngine
import com.cvlvky.cloudroll.util.Fmt
import kotlinx.coroutines.launch

private val chatTabs = listOf(MediaFilter.PHOTOS, MediaFilter.VIDEOS, MediaFilter.MUSIC, MediaFilter.VOICE, MediaFilter.FILES)

/** One chat's media, by kind, with "Save all" and, for channels, Telegram's sponsored message. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatMediaScreen(chatId: Long, start: MediaFilter) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tabName by rememberSaveable { mutableStateOf(if (start in chatTabs) start.name else MediaFilter.PHOTOS.name) }
    val tab = MediaFilter.valueOf(tabName)
    val feed = remember(chatId, tab) { Feeds.chat(chatId, tab) }
    val info = Tg.chat(chatId)
    var menu by remember { mutableStateOf(false) }
    var confirmSaveAll by remember { mutableStateOf(false) }
    var sponsored by remember(chatId) { mutableStateOf<Sponsored?>(null) }
    LaunchedEffect(chatId) {
        if (info?.kind == ChatKind.CHANNEL) sponsored = Repo.sponsored(chatId)
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(Tg.chatTitle(chatId), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = { Nav.back() }) { Icon(Icons.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { feed.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Save all ${tab.label.lowercase()} to phone") },
                        leadingIcon = { Icon(Icons.Filled.Download, null) },
                        enabled = info?.isProtected != true,
                        onClick = {
                            menu = false
                            confirmSaveAll = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Open in Telegram") },
                        leadingIcon = { Icon(Icons.Filled.OpenInNew, null) },
                        onClick = {
                            menu = false
                            scope.launch {
                                val link = Repo.chatLink(chatId)
                                if (link != null) openUrl(ctx, link) else toast(ctx, "Telegram has no link for this chat")
                            }
                        },
                    )
                }
            },
        )
        PrimaryScrollableTabRow(selectedTabIndex = chatTabs.indexOf(tab), edgePadding = 8.dp) {
            chatTabs.forEach { f ->
                Tab(selected = f == tab, onClick = { tabName = f.name }, text = { Text(f.label) })
            }
        }
        val sponsor: @Composable () -> Unit = {
            sponsored?.let { s -> SponsoredCard(s) }
        }
        when (tab) {
            MediaFilter.PHOTOS, MediaFilter.VIDEOS -> MediaGrid(
                feed = feed,
                header = if (sponsored != null) sponsor else null,
                empty = { EmptyState(Icons.Filled.FolderOpen, "Nothing here", "This chat has no ${tab.label.lowercase()}.") },
            )
            else -> MediaList(feed, tab, header = if (sponsored != null) sponsor else null)
        }
    }
    if (confirmSaveAll) {
        AlertDialog(
            onDismissRequest = { confirmSaveAll = false },
            title = { Text("Save all ${tab.label.lowercase()}?") },
            text = {
                Text(
                    "Everything of this kind in ${Tg.chatTitle(chatId)} downloads and goes into your phone's " +
                        "CloudRoll folder. Files you saved before are skipped. Files from chats that turn saving off are skipped too.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSaveAll = false
                    toast(ctx, "Collecting the list…")
                    scope.launch {
                        val all = try {
                            Repo.allInChat(chatId, tab)
                        } catch (e: Exception) {
                            emptyList()
                        }
                        if (all.isEmpty()) {
                            toast(ctx, "Nothing to save")
                        } else {
                            SaveEngine.enqueue(ctx, all, Tg.chatTitle(chatId), skipSaved = true)
                            toast(ctx, "Saving ${all.size} files. Progress is in the notification.")
                        }
                    }
                }) { Text("Save all") }
            },
            dismissButton = { TextButton(onClick = { confirmSaveAll = false }) { Text("Cancel") } },
        )
    }
}

/** Telegram's sponsored message for a channel, shown as Telegram's rules for apps require. */
@Composable
fun SponsoredCard(s: Sponsored) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(s.messageId) { Repo.sponsoredViewed(s) }
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Campaign, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    if (s.recommended) "  Recommended" else "  Sponsored",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (s.title.isNotBlank()) Text(s.title, fontWeight = FontWeight.SemiBold)
            if (s.text.isNotBlank()) Text(s.text, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
            if (s.url.isNotBlank()) {
                OutlinedButton(onClick = {
                    scope.launch { Repo.sponsoredClicked(s) }
                    openUrl(ctx, s.url)
                }) { Text(s.button.ifBlank { "Open" }) }
            }
        }
    }
}

/** Music, voice messages and files as a list. */
@Composable
fun MediaList(feed: Feed, tab: MediaFilter, header: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState()
    LaunchedEffect(feed) { if (feed.items.isEmpty()) feed.loadMore() }
    LaunchedEffect(feed, state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= state.layoutInfo.totalItemsCount - 10) feed.loadMore()
        }
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        if (header != null) item { header() }
        if (tab == MediaFilter.MUSIC || tab == MediaFilter.VOICE) {
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
        }
        itemsIndexed(feed.items, key = { _, m -> m.key }) { i, m ->
            MediaRow(m) {
                when {
                    m.isAudio -> Music.play(ctx, feed.items.toList(), i)
                    m.isImage || m.isVideo -> openViewer(feed.items.toList(), i)
                    !m.canSave -> toast(ctx, "This chat does not allow saving files")
                    else -> scope.launch {
                        toast(ctx, "Downloading ${m.displayName}…")
                        val p = Tg.localPath(m.file, 16)
                        if (p != null) openFile(ctx, p, m.mime) else toast(ctx, "Download failed")
                    }
                }
            }
        }
        item {
            when {
                feed.loading -> Loading()
                feed.error != null -> EmptyState(Icons.Filled.Refresh, "Couldn't load", feed.error.orEmpty(), action = "Try again") { feed.loadMore() }
                feed.done && feed.items.isEmpty() -> EmptyState(Icons.Filled.FolderOpen, "Nothing here", "No ${tab.label.lowercase()} in this list.")
            }
        }
    }
}

/** One song, voice message or file. */
@Composable
fun MediaRow(m: TgMedia, showChat: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            if (m.thumb != null || m.mini != null) {
                TdImage(m.thumb, m.mini, Modifier.fillMaxSize(), target = 144)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.layout.Spacer(Modifier.fillMaxSize())
                    Icon(kindIcon(m), null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(m.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            val parts = buildList {
                if (m.performer.isNotBlank()) add(m.performer)
                if (showChat) add(Tg.chatTitle(m.chatId))
                if (m.duration > 0) add(Fmt.duration(m.duration))
                add(Fmt.size(m.size))
                if (!showChat) add(Fmt.day(m.date))
            }
            Text(
                parts.joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailing != null) trailing()
    }
}
