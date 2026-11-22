package com.cvlvky.cloudroll.ui

import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.cvlvky.cloudroll.data.Db
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.ArrowBack
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
    var keyDialog by remember { mutableStateOf<String?>(null) }
    var langDialog by remember { mutableStateOf(false) }
    var regionDialog by remember { mutableStateOf(false) }
    var gridDialog by remember { mutableStateOf(false) }
    val tmdbKey by Prefs.tmdbKey.flow.collectAsState()
    val omdbKey by Prefs.omdbKey.flow.collectAsState()
    val langsSet by Prefs.movieLangs.flow.collectAsState()
    val region by Prefs.movieRegion.flow.collectAsState()
    val findMovies by Prefs.findMovies.flow.collectAsState()
    val resume by Prefs.resumeVideos.flow.collectAsState()
    val pip by Prefs.pip.flow.collectAsState()
    val grid by Prefs.gridColumns.flow.collectAsState()
    LaunchedEffect(Unit) { cache = Tg.cacheSize() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { androidx.compose.material3.IconButton(onClick = { Nav.back() }) { androidx.compose.material3.Icon(Icons.Filled.ArrowBack, "Back") } },
        )
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

            SectionLabel("Movies")
            ActionRow(
                Icons.Filled.Movie,
                "TMDB key",
                when {
                    tmdbKey.isNotBlank() -> "Set. Posters, new releases and OTT come from TMDB"
                    BuildConfig.TMDB_KEY.isNotBlank() -> "Using the key built into this app"
                    else -> "Not set. Use the same free key as ReelPulse"
                },
            ) { keyDialog = "tmdb" }
            ActionRow(
                Icons.Filled.Star,
                "OMDb key (optional)",
                if (omdbKey.isNotBlank() || BuildConfig.OMDB_KEY.isNotBlank()) "Set. IMDb, Rotten Tomatoes and Metacritic scores" else "Adds IMDb and Rotten Tomatoes scores",
            ) { keyDialog = "omdb" }
            ActionRow(Icons.Filled.Language, "Film languages", Prefs.langs().joinToString(", ") { com.cvlvky.cloudroll.movies.MOVIE_LANGS[it] ?: it }) { langDialog = true }
            ActionRow(Icons.Filled.Public, "Streaming country", com.cvlvky.cloudroll.movies.MOVIE_REGIONS[region] ?: region) { regionDialog = true }
            SwitchRow(Icons.Filled.Search, "Find films in my Telegram", "Matches the videos in your chats with TMDB, by their file names", findMovies) {
                Prefs.findMovies.value = it
            }
            ActionRow(Icons.Filled.Refresh, "Match the films again", "Forgets the matches and looks every video up again") {
                Db.clearMatches()
                com.cvlvky.cloudroll.movies.Tmdb.clearCache()
                MovieRows.clear()
                scope.launch { com.cvlvky.cloudroll.movies.TgMovies.scanLibrary(force = true) }
                toast(ctx, "Matching again")
            }
            if (com.cvlvky.cloudroll.movies.ReelPulse.installed(ctx)) {
                ActionRow(Icons.Filled.MenuBook, "Open ReelPulse", "Your diary, watchlist and OTT alerts") { com.cvlvky.cloudroll.movies.ReelPulse.launch(ctx) }
            }

            SectionLabel("Photos and videos")
            ActionRow(Icons.Filled.GridView, "Photos in a row", "$grid. You can also pinch the grid with two fingers") { gridDialog = true }
            SwitchRow(Icons.Filled.History, "Resume videos", "Start videos where you stopped, and list them in Continue watching", resume) {
                Prefs.resumeVideos.value = it
            }
            SwitchRow(Icons.Filled.PictureInPicture, "Picture-in-picture", "Keep a video playing in a small window when you leave the app", pip) {
                Prefs.pip.value = it
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
    keyDialog?.let { which ->
        KeyDialog(
            title = if (which == "tmdb") "TMDB key" else "OMDb key",
            hint = if (which == "tmdb") "themoviedb.org → Settings → API: the API Key (v3) or the API Read Access Token" else "omdbapi.com → API Key (free)",
            initial = if (which == "tmdb") Prefs.tmdbKey.value else Prefs.omdbKey.value,
            link = if (which == "tmdb") "https://www.themoviedb.org/settings/api" else "https://www.omdbapi.com/apikey.aspx",
            onDismiss = { keyDialog = null },
        ) { v ->
            if (which == "tmdb") Prefs.tmdbKey.value = v else Prefs.omdbKey.value = v
            com.cvlvky.cloudroll.movies.Tmdb.clearCache()
            MovieRows.clear()
            keyDialog = null
        }
    }
    if (langDialog) {
        val all = com.cvlvky.cloudroll.movies.MOVIE_LANGS
        AlertDialog(
            onDismissRequest = { langDialog = false },
            title = { Text("Film languages") },
            text = {
                val chosen = langsSet.toList().ifEmpty { listOf("ml", "ta", "en") }
                Column {
                    all.forEach { (code, name) ->
                        val on = code in chosen
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val now = chosen.toMutableSet()
                                if (on) {
                                    if (now.size > 1) now.remove(code)
                                } else {
                                    now.add(code)
                                }
                                Prefs.movieLangs.value = now
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(checked = on, onCheckedChange = null)
                            Text(name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { langDialog = false }) { Text("Done") } },
        )
    }
    if (regionDialog) {
        val codes = com.cvlvky.cloudroll.movies.MOVIE_REGIONS.keys.toList()
        ChoiceDialog("Streaming country", codes.map { com.cvlvky.cloudroll.movies.MOVIE_REGIONS[it] ?: it }, codes.indexOf(region).coerceAtLeast(0), onDismiss = { regionDialog = false }) {
            Prefs.movieRegion.value = codes[it]
            MovieRows.clear()
            regionDialog = false
        }
    }
    if (gridDialog) {
        val options = (2..7).toList()
        ChoiceDialog("Photos in a row", options.map { "$it" }, options.indexOf(grid).coerceAtLeast(0), onDismiss = { gridDialog = false }) {
            Prefs.gridColumns.value = options[it]
            gridDialog = false
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

@Composable
fun KeyDialog(title: String, hint: String, initial: String, link: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val ctx = LocalContext.current
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text(hint, style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.trim() },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { openUrl(ctx, link) }) { Text("Get a free key") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
