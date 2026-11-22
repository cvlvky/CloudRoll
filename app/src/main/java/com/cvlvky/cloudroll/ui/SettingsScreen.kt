package com.cvlvky.cloudroll.ui

import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cvlvky.cloudroll.BuildConfig
import com.cvlvky.cloudroll.data.Prefs
import com.cvlvky.cloudroll.tg.Tg
import com.cvlvky.cloudroll.transfer.SaveEngine
import com.cvlvky.cloudroll.update.Updater
import com.cvlvky.cloudroll.util.Fmt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by Tg.me.collectAsState()
    val theme by Prefs.theme.flow.collectAsState()
    val dynamic by Prefs.dynamicColor.flow.collectAsState()
    val lock by Prefs.appLock.flow.collectAsState()
    val hidden by Prefs.hiddenChats.flow.collectAsState()
    val limit by Prefs.cacheLimitGb.flow.collectAsState()
    val saving by SaveEngine.state.collectAsState()
    var cache by remember { mutableLongStateOf(-1L) }
    var themeDialog by remember { mutableStateOf(false) }
    var limitDialog by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { cache = Tg.cacheSize() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionLabel("Account")
            val name = listOfNotNull(me?.firstName, me?.lastName).joinToString(" ").trim().ifBlank { "Telegram account" }
            ActionRow(Icons.Filled.AccountCircle, name, me?.phoneNumber?.let { "+$it" } ?: "Signed in") {}
            ActionRow(Icons.Filled.Logout, "Sign out", "Signs CloudRoll out of Telegram on this phone") { confirmLogout = true }

            SectionLabel("Appearance")
            ActionRow(Icons.Filled.Brightness6, "Theme", listOf("Same as the phone", "Light", "Dark")[theme.coerceIn(0, 2)]) { themeDialog = true }
            SwitchRow(Icons.Filled.Palette, "Colours from the wallpaper", "Use Android's colours from your wallpaper (Android 12 and newer)", dynamic) {
                Prefs.dynamicColor.value = it
            }

            SectionLabel("Privacy")
            val canLock = remember {
                BiometricManager.from(ctx).canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                ) == BiometricManager.BIOMETRIC_SUCCESS
            }
            SwitchRow(
                Icons.Filled.Fingerprint,
                "App lock",
                if (canLock) "Ask for your fingerprint, face or screen lock when CloudRoll opens" else "Set up a screen lock on the phone first",
                lock && canLock,
            ) { if (canLock) Prefs.appLock.value = it }
            ActionRow(Icons.Filled.VisibilityOff, "Hidden chats", if (hidden.isEmpty()) "None. Press and hold a chat to hide it" else "${hidden.size} hidden from Gallery and Music") {
                Nav.go(Screen.Hidden)
            }

            SectionLabel("Storage")
            ActionRow(
                Icons.Filled.Storage,
                "Telegram cache",
                (if (cache >= 0) "${Fmt.size(cache)} used" else "Measuring…") + " · keeps up to $limit GB, oldest files go first",
            ) { limitDialog = true }
            ActionRow(Icons.Filled.DeleteOutline, "Clear the cache", "Frees the space. Pictures and videos download again when opened") {
                scope.launch {
                    Tg.trimCache(clearAll = true)
                    cache = Tg.cacheSize()
                    toast(ctx, "Cache cleared")
                }
            }
            ActionRow(
                Icons.Filled.Download,
                "Saved files",
                if (saving.running) "Saving ${saving.done + 1} of ${saving.total}…" else "Saved files go to Pictures, Movies, Music and Download, in CloudRoll folders",
            ) {}

            SectionLabel("Updates")
            ActionRow(
                Icons.Filled.SystemUpdate,
                if (checking) "Checking…" else "Check for updates",
                "You have CloudRoll ${BuildConfig.VERSION_NAME}. It also checks every time it opens.",
            ) {
                if (checking) return@ActionRow
                checking = true
                scope.launch {
                    val r = Updater.check()
                    checking = false
                    if (r == null) toast(ctx, "You have the newest version") else Updater.dismissed.value = false
                }
            }

            SectionLabel("About")
            ActionRow(Icons.Filled.Key, "Telegram API key", "api_id ${Prefs.apiId.value}. To use another, sign out first") {}
            ActionRow(Icons.Filled.Code, "CloudRoll on GitHub", "github.com/${BuildConfig.REPO}") { openUrl(ctx, "https://github.com/${BuildConfig.REPO}") }
            ActionRow(
                Icons.Filled.Info,
                "About",
                "CloudRoll ${BuildConfig.VERSION_NAME}. An unofficial app that uses the Telegram API. It is not made by Telegram.",
            ) {}
            Text(
                "Your photos, chats and sign-in stay between this phone and Telegram. CloudRoll has no server of its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (themeDialog) {
        ChoiceDialog("Theme", listOf("Same as the phone", "Light", "Dark"), theme, onDismiss = { themeDialog = false }) {
            Prefs.theme.value = it
            themeDialog = false
        }
    }
    if (limitDialog) {
        val options = listOf(1, 2, 5, 10, 20)
        ChoiceDialog("Keep up to", options.map { "$it GB" }, options.indexOf(limit).coerceAtLeast(0), onDismiss = { limitDialog = false }) {
            Prefs.cacheLimitGb.value = options[it]
            limitDialog = false
            scope.launch {
                Tg.trimCache()
                cache = Tg.cacheSize()
            }
        }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Sign out?") },
            text = { Text("CloudRoll signs out of Telegram on this phone. Your backup channel and everything in Telegram stay as they are.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    Nav.clear()
                    Feeds.clear()
                    Tg.logOut()
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { i, o ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(i) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = i == selected, onClick = { onPick(i) })
                        Text(o)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
