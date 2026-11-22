package com.cvlvky.cloudroll.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.cvlvky.cloudroll.tg.ChatInfo
import com.cvlvky.cloudroll.tg.Kind
import com.cvlvky.cloudroll.tg.Tg
import com.cvlvky.cloudroll.tg.TgMedia
import com.cvlvky.cloudroll.util.Fmt
import dev.g000sha256.tdl.dto.Minithumbnail
import java.io.File
import dev.g000sha256.tdl.dto.File as TdFile

/** Tiny blurred previews Telegram sends inside each message, decoded once. */
object ImageCache {
    private val minis = LruCache<Int, ImageBitmap>(600)

    fun mini(m: Minithumbnail): ImageBitmap? {
        val k = m.data.contentHashCode()
        minis.get(k)?.let { return it }
        val b = try {
            BitmapFactory.decodeByteArray(m.data, 0, m.data.size)?.asImageBitmap()
        } catch (e: Exception) {
            null
        } ?: return null
        minis.put(k, b)
        return b
    }
}

/** The local path of a Telegram file that is already fully on the phone, or null. */
fun readyPath(file: TdFile?): String? =
    file?.takeIf { it.local.isDownloadingCompleted && it.local.path.isNotEmpty() }?.local?.path

/**
 * A Telegram picture: the tiny blurred preview at once, then the real one once TDLib has it.
 * Coil decodes it at the size it is drawn, keeps it in memory, and turns photos the right way up.
 */
@Composable
fun TdImage(
    file: TdFile?,
    mini: Minithumbnail?,
    modifier: Modifier = Modifier,
    target: Int = 320,
    priority: Int = 2,
    contentScale: ContentScale = ContentScale.Crop,
    exif: Boolean = false,
) {
    val ctx = LocalContext.current
    var path by remember(file?.id) { mutableStateOf(readyPath(file)) }
    LaunchedEffect(file?.id) {
        if (file != null && path == null) path = Tg.localPath(file, priority)
    }
    DisposableEffect(file?.id) {
        onDispose {
            if (file != null && path == null) Tg.cancelDownload(file.id, onlyIfPending = true)
        }
    }
    val placeholder = remember(mini) { mini?.let { ImageCache.mini(it) }?.let { BitmapPainter(it) } }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        val p = path
        if (p != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(File(p)).crossfade(160).build(),
                contentDescription = null,
                placeholder = placeholder,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } else if (placeholder != null) {
            Image(placeholder, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        }
    }
}

/** A picture from the web (movie posters, cast photos, streaming logos), cached on the phone. */
@Composable
fun NetImage(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val ctx = LocalContext.current
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(url).crossfade(200).build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}

/** A square tile in a grid of photos and videos. */
@Composable
fun MediaTile(m: TgMedia, modifier: Modifier = Modifier) {
    Box(modifier) {
        if (m.thumb != null || m.mini != null) {
            TdImage(m.thumb, m.mini, Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(kindIcon(m), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            m.kind == Kind.GIF -> Badge(Icons.Filled.Gif, null, Modifier.align(Alignment.BottomStart))
            m.isVideo -> Badge(Icons.Filled.PlayCircle, if (m.duration > 0) Fmt.duration(m.duration) else Fmt.size(m.size), Modifier.align(Alignment.BottomStart))
            m.kind == Kind.FILE && !m.isImage -> Badge(null, m.fileName.substringAfterLast('.', "file").uppercase(), Modifier.align(Alignment.BottomStart))
        }
    }
}

@Composable
private fun Badge(icon: ImageVector?, text: String?, modifier: Modifier) {
    Row(
        modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
        if (text != null) Text(text, color = Color.White, fontSize = 11.sp, maxLines = 1)
    }
}

fun kindIcon(m: TgMedia): ImageVector = when {
    m.isAudio -> Icons.Filled.MusicNote
    m.isVideo -> Icons.Filled.PlayCircle
    else -> Icons.Filled.Description
}

/** A chat's round picture, or its first letter. */
@Composable
fun ChatAvatar(info: ChatInfo?, size: Dp = 44.dp) {
    val title = info?.let { if (Tg.isSavedMessages(it)) "Saved Messages" else it.title }.orEmpty()
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.trim().take(1).uppercase().ifBlank { "#" },
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
        if (info?.photo != null && !(info.let { Tg.isSavedMessages(it) })) {
            TdImage(info.photo, info.mini, Modifier.fillMaxSize(), target = 120, priority = 1)
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) OutlinedButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// ---- Intents ----

fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        toast(ctx, "No app can open this link")
    }
}

private fun uriFor(ctx: Context, path: String): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(path))

/** Shares a downloaded Telegram file with another app. */
fun shareFile(ctx: Context, path: String, mime: String) {
    try {
        val i = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uriFor(ctx, path))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        toast(ctx, "Could not share this file")
    }
}

/** Opens a downloaded Telegram file in the app the phone uses for it. */
fun openFile(ctx: Context, path: String, mime: String) {
    try {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(ctx, path), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (e: ActivityNotFoundException) {
        toast(ctx, "No app on this phone opens this kind of file")
    } catch (e: Exception) {
        toast(ctx, "Could not open this file")
    }
}
