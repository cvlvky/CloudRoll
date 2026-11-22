package com.cvlvky.cloudroll.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.ExifInterface
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.request.ImageRequest
import com.cvlvky.cloudroll.data.Db
import com.cvlvky.cloudroll.data.Match
import com.cvlvky.cloudroll.data.Prefs
import com.cvlvky.cloudroll.movies.ReelPulse
import com.cvlvky.cloudroll.movies.TgMovies
import com.cvlvky.cloudroll.movies.TitleParser
import com.cvlvky.cloudroll.player.ActiveVideo
import com.cvlvky.cloudroll.tg.Kind
import com.cvlvky.cloudroll.tg.MediaFilter
import com.cvlvky.cloudroll.tg.Repo
import com.cvlvky.cloudroll.tg.TdDataSource
import com.cvlvky.cloudroll.tg.Tg
import com.cvlvky.cloudroll.tg.TgMedia
import com.cvlvky.cloudroll.tg.guessMime
import com.cvlvky.cloudroll.tg.tdFileUri
import com.cvlvky.cloudroll.transfer.SaveEngine
import com.cvlvky.cloudroll.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import java.io.File
import kotlin.math.abs

/**
 * Full-screen photos and videos: swipe between them, pinch to zoom (big photos stay sharp through
 * sub-sampling), swipe down to close. Videos stream as they download and remember where you stopped.
 */
@Composable
fun ViewerScreen(listId: Int, start: Int) {
    val list = remember(listId) { ViewerLists.get(listId) }
    if (list.isEmpty()) {
        LaunchedEffect(Unit) { Nav.back() }
        return
    }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = ctx as? Activity
    val pager = rememberPagerState(initialPage = start.coerceIn(0, list.lastIndex)) { list.size }
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf<TgMedia?>(null) }
    var subtitlesFor by remember { mutableStateOf<TgMedia?>(null) }
    var slideshow by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val inPip by ActiveVideo.inPip
    val starred by Prefs.starred.flow.collectAsState()

    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    LaunchedEffect(slideshow, pager.currentPage) {
        if (!slideshow) return@LaunchedEffect
        chrome = false
        delay(4_000)
        if (pager.currentPage < list.lastIndex) pager.animateScrollToPage(pager.currentPage + 1) else slideshow = false
    }

    val current = list[pager.currentPage]
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = (1f - abs(dragY) / 1400f).coerceIn(0.15f, 1f)))
            .draggable(
                orientation = Orientation.Vertical,
                enabled = !zoomed && !current.isVideo && !inPip,
                state = rememberDraggableState { d -> dragY += d },
                onDragStopped = { v ->
                    if (abs(dragY) > 280f || abs(v) > 2600f) Nav.back() else dragY = 0f
                },
            ),
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().graphicsLayer { translationY = dragY },
            beyondViewportPageCount = 1,
            userScrollEnabled = !zoomed && !inPip,
            key = { list[it].key },
        ) { page ->
            val m = list[page]
            if (m.isVideo) {
                VideoPage(m, active = pager.currentPage == page, showControls = !inPip, onControls = { chrome = it })
            } else {
                PhotoPage(
                    m,
                    onTap = {
                        slideshow = false
                        chrome = !chrome
                    },
                    onZoom = { z -> if (pager.currentPage == page) zoomed = z },
                )
            }
        }
        if (chrome && !inPip) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .statusBarsPadding()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { Nav.back() }) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(Tg.chatTitle(current.chatId), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${Fmt.dateTime(current.date)}${if (list.size > 1) "  ·  ${pager.currentPage + 1} of ${list.size}" else ""}",
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val isStarred = current.key in starred
                IconButton(onClick = { Prefs.toggleStarred(current.key) }) {
                    Icon(if (isStarred) Icons.Filled.Star else Icons.Filled.StarBorder, "Star", tint = if (isStarred) Color(0xFFFFC107) else Color.White)
                }
                if (list.size > 1) {
                    IconButton(onClick = { slideshow = !slideshow }) {
                        Icon(if (slideshow) Icons.Filled.Pause else Icons.Filled.Slideshow, "Slideshow", tint = Color.White)
                    }
                }
                IconButton(onClick = { info = current }) { Icon(Icons.Filled.Info, "Details", tint = Color.White) }
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .navigationBarsPadding()
                    .padding(6.dp),
            ) {
                if (current.caption.isNotBlank() && !current.isVideo) {
                    Text(current.caption, color = Color.White, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ViewerAction(Icons.Filled.Download, "Save") {
                        if (!current.canSave) {
                            toast(ctx, "This chat does not allow saving")
                        } else {
                            SaveEngine.enqueue(ctx, listOf(current), Tg.chatTitle(current.chatId))
                            toast(ctx, "Saving to your phone")
                        }
                    }
                    ViewerAction(Icons.Filled.Share, "Share") {
                        if (!current.canSave) {
                            toast(ctx, "This chat does not allow sharing")
                        } else {
                            scope.launch {
                                toast(ctx, "Getting the file…")
                                val p = Tg.localPath(current.file, 16)
                                if (p != null) shareFile(ctx, p, current.mime) else toast(ctx, "Download failed")
                            }
                        }
                    }
                    if (current.isVideo) {
                        ViewerAction(Icons.Filled.ClosedCaption, "Subtitles") { subtitlesFor = current }
                    } else {
                        ViewerAction(Icons.Filled.Wallpaper, "Set as") {
                            scope.launch {
                                val p = Tg.localPath(current.file, 16)
                                if (p != null) setAs(ctx, p, current.mime) else toast(ctx, "Download failed")
                            }
                        }
                    }
                    ViewerAction(Icons.Filled.Chat, "Chat") { Nav.go(Screen.ChatMedia(current.chatId)) }
                    ViewerAction(Icons.Filled.OpenInNew, "Telegram") {
                        scope.launch {
                            val link = Repo.messageLink(current.chatId, current.messageId) ?: Repo.chatLink(current.chatId)
                            if (link != null) openUrl(ctx, link) else toast(ctx, "Telegram has no link for this message")
                        }
                    }
                }
            }
        }
    }
    info?.let { m -> InfoDialog(m) { info = null } }
    subtitlesFor?.let { m -> SubtitleDialog(m) { subtitlesFor = null } }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, label, tint = Color.White) }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

/** "Set as": wallpaper, contact photo, or whatever the phone offers. */
private fun setAs(ctx: android.content.Context, path: String, mime: String) {
    try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(path))
        val i = Intent(Intent.ACTION_ATTACH_DATA)
            .setDataAndType(uri, mime)
            .putExtra("mimeType", mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Set as").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        toast(ctx, "Nothing on this phone can use it")
    }
}

@Composable
private fun PhotoPage(m: TgMedia, onTap: () -> Unit, onZoom: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var path by remember(m.key) { mutableStateOf(readyPath(m.file)) }
    var failed by remember(m.key) { mutableStateOf(false) }
    val progress by Tg.fileFlow(m.file.id).collectAsState()
    LaunchedEffect(m.key) {
        if (path == null) {
            Tg.startDownload(m.file.id, priority = 24)
            path = Tg.localPath(m.file, 24)
            if (path == null) failed = true
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val p = path
        if (p != null) {
            val state = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 8f)))
            val isZoomed = (state.zoomableState.zoomFraction ?: 0f) > 0.02f
            LaunchedEffect(isZoomed) { onZoom(isZoomed) }
            ZoomableAsyncImage(
                model = ImageRequest.Builder(ctx).data(File(p)).build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                state = state,
                onClick = { onTap() },
            )
        } else {
            TdImage(m.thumb, m.mini, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            if (failed) {
                Text("Could not load this picture", color = Color.White)
            } else {
                val f = progress
                val total = f?.let { if (it.size > 0) it.size else it.expectedSize } ?: 0L
                val done = f?.local?.downloadedSize ?: 0L
                if (total > 0 && done > 0) {
                    CircularProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, color = Color.White)
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

/** The container type for the player, from Telegram's MIME type or the file name. */
private fun streamType(m: TgMedia): String? {
    val t = if (m.mime.isBlank() || m.mime == "application/octet-stream") guessMime(m.fileName) else m.mime
    return t.takeIf { it.startsWith("video/") || it.startsWith("audio/") }
}

private fun savePosition(m: TgMedia, player: Player) {
    if (m.kind == Kind.GIF) return
    val pos = player.currentPosition
    val dur = player.duration.takeIf { it > 0 } ?: 0L
    if (pos <= 0) return
    if (dur > 0 && pos >= dur * 0.94) {
        Db.removePosition(m.key)
        return
    }
    val title = TgMovies.parsedOf(m)?.title ?: m.displayName
    Db.savePosition(m.key, m.chatId, m.messageId, pos, dur, title)
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPage(m: TgMedia, active: Boolean, showControls: Boolean, onControls: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    if (!active) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TdImage(m.thumb, m.mini, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        return
    }
    var resumedAt by remember(m.key) { mutableLongStateOf(0L) }
    var ended by remember(m.key) { mutableStateOf(false) }
    var film by remember(m.key) { mutableStateOf<Match?>(null) }
    val player = remember(m.key) {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, TdDataSource.Factory())))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(tdFileUri(m.file.id, streamType(m))))
                repeatMode = if (m.kind == Kind.GIF) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                val saved = if (Prefs.resumeVideos.value && m.kind != Kind.GIF) Db.position(m.key) else null
                if (saved != null && saved.position > 30_000 && (saved.duration <= 0 || saved.position < saved.duration - 60_000)) {
                    seekTo(saved.position)
                    resumedAt = saved.position
                }
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(m.key) {
        ActiveVideo.set(player, m.width, m.height)
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ActiveVideo.sync(activity, isPlaying)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) ActiveVideo.set(player, videoSize.width, videoSize.height)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && m.kind != Kind.GIF) {
                    Db.removePosition(m.key)
                    ended = true
                }
            }
        }
        player.addListener(listener)
        onDispose {
            savePosition(m, player)
            player.removeListener(listener)
            ActiveVideo.clear(player)
            ActiveVideo.sync(activity, false)
            player.release()
        }
    }
    LaunchedEffect(m.key) {
        while (true) {
            delay(5_000)
            if (player.isPlaying) savePosition(m, player)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        savePosition(m, player)
        player.pause()
    }
    LaunchedEffect(resumedAt) {
        if (resumedAt > 0) {
            delay(6_000)
            resumedAt = 0
        }
    }
    LaunchedEffect(ended) {
        if (ended && ReelPulse.installed(ctx)) film = TgMovies.matchFor(m)
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    setShowSubtitleButton(true)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> onControls(v == View.VISIBLE) })
                    setFullscreenButtonClickListener { full ->
                        activity?.requestedOrientation =
                            if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                    this.player = player
                }
            },
            update = {
                it.player = player
                it.useController = showControls
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (resumedAt > 0 && showControls) {
            Card(
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Resumed at ${Fmt.durationMs(resumedAt)}")
                    TextButton(onClick = {
                        player.seekTo(0)
                        resumedAt = 0
                    }) { Text("Start over") }
                }
            }
        }
        val f = film
        if (ended && f != null && showControls) {
            Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Finished ${f.title}?", style = MaterialTheme.typography.titleMedium)
                    Text("Log it and rate it in your ReelPulse diary.", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = {
                            ReelPulse.open(ctx, f.tmdbId)
                            film = null
                        }) { Text("Open in ReelPulse") }
                        TextButton(onClick = { film = null }) { Text("Not now") }
                    }
                }
            }
        }
    }
}

/** Subtitle files posted in the same chat, best name match first. */
@Composable
private fun SubtitleDialog(m: TgMedia, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember(m.key) { mutableStateOf<List<TgMedia>?>(null) }
    LaunchedEffect(m.key) {
        val exts = setOf("srt", "vtt", "ass", "ssa")
        val found = exts.flatMap { q ->
            runCatching { Repo.searchChat(m.chatId, MediaFilter.FILES, q, "").items }.getOrDefault(emptyList())
        }.filter { it.fileName.substringAfterLast('.', "").lowercase() in exts }.distinctBy { it.key }
        val base = TgMovies.parsedOf(m)?.title ?: m.displayName
        list = found.sortedByDescending { TitleParser.similarity(TitleParser.parse(it.fileName)?.title ?: it.fileName, base) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subtitles from this chat") },
        text = {
            val l = list
            when {
                l == null -> Loading()
                l.isEmpty() -> Text("No .srt, .vtt or .ass file was posted in this chat. Subtitles inside the video file are under the player's subtitle button.")
                else -> LazyColumn {
                    items(l, key = { it.key }) { s ->
                        Text(
                            s.fileName,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        val path = Tg.localPath(s.file, 24)
                                        val player = ActiveVideo.player
                                        if (path == null || player == null) {
                                            toast(ctx, "Couldn't load that subtitle")
                                        } else {
                                            applySubtitle(player, path, s.fileName.substringAfterLast('.', "srt").lowercase())
                                            toast(ctx, "Subtitles on")
                                        }
                                        onDismiss()
                                    }
                                }
                                .padding(vertical = 10.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(UnstableApi::class)
private fun applySubtitle(player: ExoPlayer, path: String, ext: String) {
    val mime = when (ext) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }
    val current = player.currentMediaItem ?: return
    val position = player.currentPosition
    val sub = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(path)))
        .setMimeType(mime)
        .setLanguage("und")
        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        .build()
    player.setMediaItem(current.buildUpon().setSubtitleConfigurations(listOf(sub)).build(), position)
    player.prepare()
    player.play()
}

/** Details of a photo or video, with the camera and place for original photos. */
@Composable
private fun InfoDialog(m: TgMedia, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var exif by remember(m.key) { mutableStateOf<List<String>>(emptyList()) }
    var place by remember(m.key) { mutableStateOf<DoubleArray?>(null) }
    LaunchedEffect(m.key) {
        val p = readyPath(m.file) ?: return@LaunchedEffect
        if (!m.isImage) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                val e = ExifInterface(p)
                val camera = listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" ").trim()
                val lines = listOfNotNull(
                    camera.takeIf { it.isNotBlank() }?.let { "Camera: $it" },
                    e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { "Taken: $it" },
                    e.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "Aperture: f/$it" },
                    e.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let { t -> "Shutter: " + if (t < 1) "1/${(1 / t).toInt()} s" else "$t s" },
                    e.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { "ISO $it" },
                )
                val ll = FloatArray(2)
                val geo = if (e.getLatLong(ll)) doubleArrayOf(ll[0].toDouble(), ll[1].toDouble()) else null
                exif = lines
                place = geo
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            val geo = place
            if (geo != null) {
                TextButton(onClick = { openUrl(ctx, "geo:${geo[0]},${geo[1]}?q=${geo[0]},${geo[1]}") }) { Text("Show place") }
            }
        },
        title = { Text(m.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Chat: ${Tg.chatTitle(m.chatId)}")
                Text("Sent: ${Fmt.dateTime(m.date)}")
                Text("Size: ${Fmt.size(m.size)}")
                if (m.width > 0) Text("Dimensions: ${m.width} × ${m.height}")
                if (m.duration > 0) Text("Length: ${Fmt.duration(m.duration)}")
                if (m.fileName.isNotBlank()) Text("File: ${m.fileName}")
                exif.forEach { Text(it) }
                if (!m.canSave) Text("Saving and sharing are turned off in this chat.")
                if (m.caption.isNotBlank()) Text(m.caption, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
        },
    )
}
