package com.cvlvky.cloudroll.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cvlvky.cloudroll.data.Prefs
import com.cvlvky.cloudroll.tg.ChatInfo
import com.cvlvky.cloudroll.tg.ChatKind
import com.cvlvky.cloudroll.tg.Repo
import com.cvlvky.cloudroll.tg.Tg
import kotlinx.coroutines.launch

private enum class ChatGroup(val label: String) { ALL("All"), CHANNELS("Channels"), GROUPS("Groups"), PEOPLE("People") }

/** Your chats and channels; open one to see its photos, videos, music and files. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val chats by Tg.chats.collectAsState()
    val me by Tg.me.collectAsState()
    val hidden by Prefs.hiddenChats.flow.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var groupName by rememberSaveable { mutableStateOf(ChatGroup.ALL.name) }
    val group = ChatGroup.valueOf(groupName)
    var menuFor by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { Tg.loadAllChats() }
    val list = remember(chats, query, group, me) {
        chats.values
            .filter { it.order != 0L || Tg.isSavedMessages(it) }
            .filter {
                when (group) {
                    ChatGroup.ALL -> true
                    ChatGroup.CHANNELS -> it.kind == ChatKind.CHANNEL
                    ChatGroup.GROUPS -> it.kind == ChatKind.GROUP
                    ChatGroup.PEOPLE -> it.kind == ChatKind.PRIVATE
                }
            }
            .filter { query.isBlank() || Tg.chatTitle(it.id).contains(query.trim(), ignoreCase = true) }
            .sortedWith(compareByDescending<ChatInfo> { Tg.isSavedMessages(it) }.thenByDescending { it.order })
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Chats") })
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search chats") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChatGroup.entries.forEach { g ->
                FilterChip(selected = g == group, onClick = { groupName = g.name }, label = { Text(g.label) })
            }
        }
        if (list.isEmpty()) {
            if (chats.isEmpty()) Loading() else EmptyState(Icons.Filled.Chat, "No chats", "No chat matches this search.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { c ->
                val isHidden = hidden.contains(c.id.toString())
                Box {
                    ListItem(
                        headlineContent = { Text(Tg.chatTitle(c.id), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                when {
                                    Tg.isSavedMessages(c) -> "Your own cloud chat"
                                    c.kind == ChatKind.CHANNEL -> "Channel"
                                    c.kind == ChatKind.GROUP -> "Group"
                                    else -> "Person"
                                } + if (isHidden) " · hidden from Gallery and Music" else "",
                                maxLines = 1,
                            )
                        },
                        leadingContent = { ChatAvatar(c) },
                        trailingContent = {
                            if (isHidden) Icon(Icons.Filled.VisibilityOff, "Hidden", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = { Nav.go(Screen.ChatMedia(c.id)) },
                            onLongClick = { menuFor = c.id },
                        ),
                    )
                    DropdownMenu(expanded = menuFor == c.id, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(
                            text = { Text(if (isHidden) "Show in Gallery and Music" else "Hide from Gallery and Music") },
                            onClick = {
                                menuFor = null
                                Prefs.toggleHidden(c.id)
                                Feeds.clearAcrossChats()
                            },
                        )
                        DropdownMenuItem(text = { Text("Open in Telegram") }, onClick = {
                            menuFor = null
                            scope.launch {
                                val link = Repo.chatLink(c.id)
                                if (link != null) openUrl(ctx, link) else toast(ctx, "Telegram has no link for this chat")
                            }
                        })
                    }
                }
                HorizontalDivider(Modifier.padding(start = 72.dp))
            }
        }
    }
}

/** The chats hidden from Gallery and Music, to bring them back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenChatsScreen() {
    val hidden by Prefs.hiddenChats.flow.collectAsState()
    val chats by Tg.chats.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Hidden chats") },
            navigationIcon = { IconButton(onClick = { Nav.back() }) { Icon(Icons.Filled.ArrowBack, "Back") } },
        )
        if (hidden.isEmpty()) {
            EmptyState(Icons.Filled.VisibilityOff, "No hidden chats", "Press and hold a chat in Chats to hide it from Gallery and Music.")
            return@Column
        }
        LazyColumn {
            items(hidden.toList(), key = { it }) { idText ->
                val id = idText.toLongOrNull() ?: return@items
                val info = chats[id]
                ListItem(
                    headlineContent = { Text(if (info != null) Tg.chatTitle(id) else "Chat $id") },
                    leadingContent = { ChatAvatar(info, 40.dp) },
                    trailingContent = {
                        TextButton(onClick = {
                            Prefs.toggleHidden(id)
                            Feeds.clearAcrossChats()
                        }) { Text("Show") }
                    },
                )
            }
        }
    }
}

@Composable
fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(icon, null, Modifier.size(18.dp))
}
