package com.cvlvky.cloudroll.ui

import android.app.Activity
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.cvlvky.cloudroll.tg.Repo
import com.cvlvky.cloudroll.tg.TdDataSource
import com.cvlvky.cloudroll.tg.Tg
import com.cvlvky.cloudroll.tg.TgMedia
import com.cvlvky.cloudroll.tg.tdFileUri
import com.cvlvky.cloudroll.transfer.SaveEngine
import com.cvlvky.cloudroll.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image

/** Full-screen photos and videos: swipe between them, pinch to zoom, videos stream as they download. */
@Composable
fun ViewerScreen(listId: Int, start: Int) {
    val list = remember(listId) { ViewerLists.get(listId) }
    if (list.isEmpty()) {
        LaunchedEffect(Unit) { Nav.back() }
        return
    }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = start.coerceIn(0, list.lastIndex)) { list.size }
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf<TgMedia?>(null) }

    // Full screen while the viewer is open.
    val activity = ctx as? Activity
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { list[it].key }) { page ->
            val m = list[page]
            if (m.isVideo) {
                VideoPage(m, active = pager.currentPage == page, onControls = { chrome = it })
            } else {
                PhotoPage(m, onTap = { chrome = !chrome })
            }
        }
        val current = list[pager.currentPage]
        if (chrome) {
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
                    Text(Fmt.dateTime(current.date), color = Color.White.copy(alpha = 0.75f), maxLines = 1)
                }
                IconButton(onClick = { info = current }) { Icon(Icons.Filled.Info, "Details", tint = Color.White) }
            }
            run {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .navigationBarsPadding()
                        .padding(8.dp),
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
    }
    info?.let { m ->
        AlertDialog(
            onDismissRequest = { info = null },
            confirmButton = { TextButton(onClick = { info = null }) { Text("Close") } },
            title = { Text(m.displayName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Chat: ${Tg.chatTitle(m.chatId)}")
                    Text("Sent: ${Fmt.dateTime(m.date)}")
                    Text("Size: ${Fmt.size(m.size)}")
                    if (m.width > 0) Text("Dimensions: ${m.width} × ${m.height}")
                    if (m.duration > 0) Text("Length: ${Fmt.duration(m.duration)}")
                    if (m.fileName.isNotBlank()) Text("File: ${m.fileName}")
                    if (!m.canSave) Text("Saving and sharing are turned off in this chat.")
                    if (m.caption.isNotBlank()) Text(m.caption)
                }
            },
        )
    }
}

@Composable
private fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, label, tint = Color.White) }
        Text(label, color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PhotoPage(m: TgMedia, onTap: () -> Unit) {
    var full by remember(m.key) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(m.key) { mutableStateOf(false) }
    val progress by Tg.fileFlow(m.file.id).collectAsState()
    LaunchedEffect(m.key) {
        Tg.startDownload(m.file.id, priority = 24)
        val path = Tg.localPath(m.file, 24)
        if (path == null) {
            failed = true
            return@LaunchedEffect
        }
        full = withContext(Dispatchers.IO) { ImageCache.load("full:${m.file.id}", path, 2160, exif = m.kind != com.cvlvky.cloudroll.tg.Kind.PHOTO) }
        if (full == null) failed = true
    }
    var scale by remember(m.key) { mutableFloatStateOf(1f) }
    var offset by remember(m.key) { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(m.key) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            }
            .pointerInput(m.key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (event.changes.size > 1 || scale > 1f) {
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val img = full
        if (img != null) {
            Image(
                img,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
        } else {
            TdImage(m.thumb, m.mini, Modifier.fillMaxSize(), target = 480, contentScale = ContentScale.Fit)
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
    val t = if (m.mime.isBlank() || m.mime == "application/octet-stream") com.cvlvky.cloudroll.tg.guessMime(m.fileName) else m.mime
    return t.takeIf { it.startsWith("video/") || it.startsWith("audio/") }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPage(m: TgMedia, active: Boolean, onControls: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    if (!active) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TdImage(m.thumb, m.mini, Modifier.fillMaxSize(), target = 480, contentScale = ContentScale.Fit)
        }
        return
    }
    val player = remember(m.key) {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(TdDataSource.Factory()))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(tdFileUri(m.file.id, streamType(m))))
                repeatMode = if (m.kind == com.cvlvky.cloudroll.tg.Kind.GIF) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(m.key) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                setBackgroundColor(android.graphics.Color.BLACK)
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> onControls(v == android.view.View.VISIBLE) })
                this.player = player
            }
        },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}
