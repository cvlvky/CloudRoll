package com.cvlvky.cloudroll.ui

import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.cvlvky.cloudroll.backup.BackupEngine
import com.cvlvky.cloudroll.backup.BackupScheduler
import com.cvlvky.cloudroll.backup.Bucket
import com.cvlvky.cloudroll.backup.LocalMedia
import com.cvlvky.cloudroll.data.Db
import com.cvlvky.cloudroll.data.Prefs
import com.cvlvky.cloudroll.tg.MediaFilter
import com.cvlvky.cloudroll.tg.Repo
import com.cvlvky.cloudroll.transfer.SaveEngine
import com.cvlvky.cloudroll.transfer.TransferService
import com.cvlvky.cloudroll.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Free unlimited backup of the phone's photos and videos to a private Telegram channel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by BackupEngine.state.collectAsState()
    val enabled by Prefs.backupEnabled.flow.collectAsState()
    val wifiOnly by Prefs.backupWifiOnly.flow.collectAsState()
    val charging by Prefs.backupChargingOnly.flow.collectAsState()
    val videos by Prefs.backupVideos.flow.collectAsState()
    val chosen by Prefs.backupFolders.flow.collectAsState()
    val chatId by Prefs.backupChatId.flow.collectAsState()
    val lastRun by Prefs.backupLastRun.flow.collectAsState()

    var access by remember { mutableStateOf(LocalMedia.hasAccess(ctx)) }
    var buckets by remember { mutableStateOf<List<Bucket>>(emptyList()) }
    var pending by remember { mutableIntStateOf(-1) }
    var backedUp by remember { mutableIntStateOf(0) }
    var freeable by remember { mutableStateOf<List<Pair<android.net.Uri, Long>>?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        access = LocalMedia.hasAccess(ctx)
        onPauseOrDispose { }
    }
    LaunchedEffect(access, chosen, videos, state.running, chatId) {
        if (!access) return@LaunchedEffect
        val (b, p, d) = withContext(Dispatchers.IO) {
            Triple(LocalMedia.buckets(ctx), BackupEngine.pendingCount(ctx), Db.backedUpCount(Prefs.backupChatId.value))
        }
        buckets = b
        pending = p
        backedUp = d
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = LocalMedia.hasAccess(ctx)
    }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) toast(ctx, "Space freed. Your photos stay safe in the backup channel.")
        freeable = null
    }
    val selected = remember(chosen, buckets) { if (access) LocalMedia.chosenBuckets(ctx, chosen) else emptySet() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Backup") })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Icon(if (pending == 0) Icons.Filled.CloudDone else Icons.Filled.CloudUpload, null)
                        Text(
                            "  " + when {
                                state.running -> "Backing up…"
                                !access -> "Photo access needed"
                                pending == 0 -> "Everything is backed up"
                                pending > 0 -> "$pending waiting to back up"
                                else -> "Checking…"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        "Photos and videos go to your private Telegram channel \"CloudRoll Backup\" as original files: full quality, free, unlimited. " +
                            "Each file can be up to 2 GB (4 GB with Telegram Premium).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Backed up: $backedUp · Last backup: ${Fmt.ago(lastRun)}", style = MaterialTheme.typography.bodySmall)
                    if (state.running) {
                        val total = state.total.coerceAtLeast(1)
                        LinearProgressIndicator(progress = { state.done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                        Text("${state.done + 1} of ${state.total}: ${state.currentName} · ${state.currentPct}%", style = MaterialTheme.typography.bodySmall)
                    } else if (state.message.isNotBlank()) {
                        Text(state.message, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.running) {
                            OutlinedButton(onClick = { BackupEngine.stop() }) { Text("Stop") }
                        } else {
                            Button(
                                onClick = {
                                    if (!access) permission.launch(LocalMedia.permissions()) else TransferService.start(ctx, backup = true)
                                },
                            ) { Text(if (access) "Back up now" else "Allow photo access") }
                        }
                    }
                }
            }

            if (access && LocalMedia.hasPartialAccess(ctx)) {
                Text(
                    "CloudRoll can only see the photos you picked. To back up everything, allow access to all photos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = { permission.launch(LocalMedia.permissions()) }) { Text("Allow all photos") }
            }

            SectionLabel("Automatic backup")
            SwitchRow(Icons.Filled.Schedule, "Back up automatically", "New photos go up soon after you take them, and every few hours", enabled) {
                Prefs.backupEnabled.value = it
                BackupScheduler.update(ctx)
                if (it && !access) permission.launch(LocalMedia.permissions())
            }
            SwitchRow(Icons.Filled.Wifi, "Only on Wi-Fi", "Automatic backup waits for Wi-Fi so it doesn't use mobile data", wifiOnly) {
                Prefs.backupWifiOnly.value = it
                BackupScheduler.update(ctx)
            }
            SwitchRow(Icons.Filled.BatteryChargingFull, "Only while charging", "Automatic backup waits until the phone is charging", charging) {
                Prefs.backupChargingOnly.value = it
                BackupScheduler.update(ctx)
            }
            SwitchRow(Icons.Filled.Videocam, "Include videos", "Back up videos as well as photos", videos) {
                Prefs.backupVideos.value = it
            }

            if (access) {
                SectionLabel("Folders to back up")
                if (buckets.isEmpty()) Text("No photo folders found.", style = MaterialTheme.typography.bodySmall)
                buckets.forEach { b ->
                    val on = b.id in selected
                    ListItem(
                        headlineContent = { Text(b.name) },
                        supportingContent = { Text("${b.count} items") },
                        leadingContent = { Icon(Icons.Filled.Folder, null) },
                        trailingContent = {
                            Checkbox(checked = on, onCheckedChange = { c ->
                                val s = selected.toMutableSet()
                                if (c) s.add(b.id) else s.remove(b.id)
                                Prefs.backupFolders.value = s
                            })
                        },
                        modifier = Modifier.clickable {
                            val s = selected.toMutableSet()
                            if (on) s.remove(b.id) else s.add(b.id)
                            Prefs.backupFolders.value = s
                        },
                    )
                }
            }

            SectionLabel("Your backup")
            if (chatId == 0L) {
                Text("The backup channel is made the first time you back up.", style = MaterialTheme.typography.bodySmall)
            } else {
                ActionRow(Icons.Filled.PhotoLibrary, "See the backup in CloudRoll", "Browse the files in your backup channel") {
                    Nav.go(Screen.ChatMedia(chatId, MediaFilter.FILES))
                }
                ActionRow(Icons.Filled.OpenInNew, "Open the backup in Telegram", "The private channel \"CloudRoll Backup\"") {
                    scope.launch {
                        val link = Repo.chatLink(chatId)
                        if (link != null) openUrl(ctx, link) else toast(ctx, "Back up something first")
                    }
                }
                ActionRow(Icons.Filled.DeleteSweep, "Free up space", "Delete photos and videos from this phone that are already backed up") {
                    scope.launch {
                        val list = withContext(Dispatchers.IO) { BackupEngine.freeable(ctx) }
                        if (list.isEmpty()) toast(ctx, "Nothing to free: no backed-up file is still on this phone") else freeable = list
                    }
                }
                ActionRow(Icons.Filled.Restore, "Restore to this phone", "Download every backed-up file into Pictures/CloudRoll/Restored") {
                    confirmRestore = true
                }
            }
            Text(
                "Tip: the backup is a normal Telegram channel. Don't delete it, and don't leave it, or the backup is gone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }

    freeable?.let { list ->
        val total = list.sumOf { it.second }
        AlertDialog(
            onDismissRequest = { freeable = null },
            title = { Text("Free up ${Fmt.size(total)}?") },
            text = {
                Text(
                    "${list.size} photos and videos are already in your backup channel. They will be removed from this phone only. " +
                        "Android asks you to confirm next.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 30) {
                        val pi = MediaStore.createDeleteRequest(ctx.contentResolver, list.map { it.first })
                        deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    } else {
                        toast(ctx, "This needs Android 11 or newer")
                        freeable = null
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { freeable = null }) { Text("Cancel") } },
        )
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore the backup?") },
            text = { Text("Every file in your backup channel downloads into Pictures/CloudRoll/Restored and Movies/CloudRoll/Restored. This can take a while and use a lot of data.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    toast(ctx, "Collecting the list…")
                    scope.launch {
                        val all = try {
                            Repo.allInChat(chatId, MediaFilter.FILES)
                        } catch (e: Exception) {
                            emptyList()
                        }
                        if (all.isEmpty()) toast(ctx, "The backup is empty") else {
                            SaveEngine.enqueue(ctx, all, "Restored", skipSaved = true)
                            toast(ctx, "Restoring ${all.size} files. Progress is in the notification.")
                        }
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SwitchRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
