package com.cvlvky.cloudroll.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.cvlvky.cloudroll.BuildConfig
import com.cvlvky.cloudroll.update.Updater
import com.cvlvky.cloudroll.util.Fmt
import kotlinx.coroutines.launch
import java.io.File

/** Checks for a newer CloudRoll each time the app opens, and offers it in a pop-up. */
@Composable
fun UpdatePrompt() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rel by Updater.available.collectAsState()
    val dismissed by Updater.dismissed.collectAsState()
    val progress by Updater.progress.collectAsState()
    val error by Updater.error.collectAsState()
    var apk by remember { mutableStateOf<File?>(null) }
    var waitingForPermission by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { Updater.check() }
    LifecycleResumeEffect(Unit) {
        // Back from "Allow from this source": carry on with the install.
        val file = apk
        if (waitingForPermission && file != null && Updater.canInstall(ctx)) {
            waitingForPermission = false
            Updater.install(ctx, file)
        }
        if (System.currentTimeMillis() - Updater.lastCheck > 6 * 3600_000L) scope.launch { Updater.check() }
        onPauseOrDispose { }
    }

    val r = rel ?: return
    if (dismissed) return
    AlertDialog(
        onDismissRequest = { if (progress == null) Updater.dismissed.value = true },
        title = { Text("Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("CloudRoll ${r.version} is ready (you have ${BuildConfig.VERSION_NAME})${if (r.size > 0) " · ${Fmt.size(r.size)}" else ""}.")
                if (r.notes.isNotEmpty()) {
                    Text("What's new", fontWeight = FontWeight.SemiBold)
                    r.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
                val p = progress
                if (p != null && apk == null) {
                    LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("Downloading… $p%", style = MaterialTheme.typography.bodySmall)
                }
                if (waitingForPermission) {
                    Text("Almost there: turn on \"Allow from this source\" for CloudRoll, then come back.", style = MaterialTheme.typography.bodySmall)
                } else if (apk != null) {
                    Text("Tap Update on Android's screen. CloudRoll restarts with the new version and keeps your sign-in.", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            val file = apk
            when {
                file != null -> TextButton(onClick = {
                    if (!Updater.install(ctx, file)) waitingForPermission = true
                }) { Text("Install") }
                progress != null -> TextButton(onClick = {}, enabled = false) { Text("Downloading…") }
                else -> TextButton(onClick = {
                    scope.launch {
                        val f = Updater.download(ctx, r)
                        if (f != null) {
                            apk = f
                            if (!Updater.install(ctx, f)) waitingForPermission = true
                        }
                    }
                }) { Text("Update now") }
            }
        },
        dismissButton = {
            if (progress == null || apk != null) {
                TextButton(onClick = { Updater.dismissed.value = true }) { Text("Later") }
            }
        },
    )
}
