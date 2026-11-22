package com.cvlvky.cloudroll.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cvlvky.cloudroll.player.Music
import kotlinx.coroutines.delay

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Gallery", Icons.Filled.PhotoLibrary),
    Tab("Chats", Icons.Filled.Chat),
    Tab("Music", Icons.Filled.LibraryMusic),
    Tab("Backup", Icons.Filled.CloudUpload),
    Tab("Settings", Icons.Filled.Settings),
)

/** The signed-in app: the five tabs, with screens opened on top of them. */
@Composable
fun MainApp() {
    val holder = rememberSaveableStateHolder()
    val top = Nav.stack.lastOrNull()
    BackHandler(enabled = top != null) { Nav.back() }
    Box(Modifier.fillMaxSize()) {
        if (top == null) {
            holder.SaveableStateProvider("home") { HomeTabs() }
        } else {
            holder.SaveableStateProvider(top.key) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (top) {
                        is Screen.ChatMedia -> ChatMediaScreen(top.chatId, top.start)
                        is Screen.Viewer -> ViewerScreen(top.listId, top.index)
                        Screen.Player -> PlayerScreen()
                        Screen.Hidden -> HiddenChatsScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTabs() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val holder = rememberSaveableStateHolder()
    Scaffold(
        bottomBar = {
            Column {
                MiniPlayer()
                NavigationBar {
                    tabs.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            holder.SaveableStateProvider("tab$tab") {
                when (tab) {
                    0 -> GalleryScreen()
                    1 -> ChatsScreen()
                    2 -> MusicScreen()
                    3 -> BackupScreen()
                    else -> SettingsScreen()
                }
            }
        }
    }
}

/** The song playing now, above the tabs. Tap it for the full player. */
@Composable
fun MiniPlayer() {
    val now by Music.state.collectAsState()
    if (!now.active) return
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(now.key, now.playing) {
        while (true) {
            val d = Music.duration()
            progress = if (d > 0) (Music.position().toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }
    Surface(tonalElevation = 3.dp) {
        Column {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { Nav.go(Screen.Player) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    val cur = Music.current()
                    if (cur?.thumb != null || cur?.mini != null) {
                        TdImage(cur.thumb, cur.mini, Modifier.fillMaxSize(), target = 120)
                    } else {
                        Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(now.title.ifBlank { "Playing" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    if (now.artist.isNotBlank()) {
                        Text(
                            now.artist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { Music.toggle() }) {
                    Icon(if (now.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (now.playing) "Pause" else "Play")
                }
                IconButton(onClick = { Music.next() }) { Icon(Icons.Filled.SkipNext, contentDescription = "Next") }
            }
        }
    }
}
