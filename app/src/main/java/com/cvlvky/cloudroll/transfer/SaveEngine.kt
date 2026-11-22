package com.cvlvky.cloudroll.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.cvlvky.cloudroll.data.Db
import com.cvlvky.cloudroll.tg.Kind
import com.cvlvky.cloudroll.tg.TgMedia
import com.cvlvky.cloudroll.tg.Tg
import com.cvlvky.cloudroll.util.Fmt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue

/** Saves Telegram photos, videos, music and files into the phone's own folders. */
object SaveEngine {

    data class State(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val saved: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val currentName: String = "",
        val currentPct: Int = 0,
        val message: String = "",
    )

    private data class Item(val media: TgMedia, val folder: String, val skipSaved: Boolean)

    val state = MutableStateFlow(State())
    private val queue = ConcurrentLinkedQueue<Item>()
    private val mutex = Mutex()

    @Volatile
    private var cancelled = false

    /** Adds files to the save queue and starts the transfer service. */
    fun enqueue(ctx: Context, items: List<TgMedia>, folder: String, skipSaved: Boolean = false) {
        if (items.isEmpty()) return
        cancelled = false
        items.forEach { queue.add(Item(it, folder, skipSaved)) }
        state.update { s ->
            if (s.running) s.copy(total = s.total + items.size) else State(running = true, total = items.size, message = "Starting")
        }
        TransferService.start(ctx, backup = false)
    }

    fun cancel() {
        cancelled = true
        queue.clear()
    }

    fun hasWork(): Boolean = queue.isNotEmpty()

    suspend fun drain(ctx: Context) = mutex.withLock {
        state.update { it.copy(running = true) }
        while (!cancelled) {
            val item = queue.poll() ?: break
            state.update { it.copy(currentName = item.media.displayName, currentPct = 0) }
            val outcome = saveOne(ctx, item)
            state.update {
                when (outcome) {
                    1 -> it.copy(done = it.done + 1, saved = it.saved + 1)
                    0 -> it.copy(done = it.done + 1, skipped = it.skipped + 1)
                    else -> it.copy(done = it.done + 1, failed = it.failed + 1)
                }
            }
        }
        state.update { s ->
            val msg = buildString {
                append(if (s.saved == 1) "1 file saved to your phone" else "${s.saved} files saved to your phone")
                if (s.skipped > 0) append(", ${s.skipped} skipped")
                if (s.failed > 0) append(", ${s.failed} failed")
                append('.')
            }
            s.copy(running = false, message = if (cancelled) "Stopped. $msg" else msg)
        }
        cancelled = false
    }

    /** 1 saved, 0 skipped (not allowed or already saved), -1 failed. */
    private suspend fun saveOne(ctx: Context, item: Item): Int {
        val m = item.media
        if (!m.canSave) return 0
        if (item.skipSaved && Db.isSaved(m.key)) return 0
        val path = coroutineScope {
            val watcher = launch {
                Tg.fileFlow(m.file.id).collect { f ->
                    if (f != null) {
                        val total = if (f.size > 0) f.size else f.expectedSize
                        if (total > 0) state.update { it.copy(currentPct = (f.local.downloadedSize * 100 / total).toInt().coerceIn(0, 100)) }
                    }
                }
            }
            try {
                Tg.localPath(m.file, priority = 8)
            } finally {
                watcher.cancel()
            }
        } ?: return -1
        val taken = takenFromCaption(m.caption) ?: (m.date * 1000L)
        val ok = MediaSaver.save(ctx, File(path), fileNameFor(m), m.mime, m.kind, item.folder, taken) != null
        if (ok) Db.markSaved(m.key)
        return if (ok) 1 else -1
    }

    /** Backups made by CloudRoll carry the date the photo was taken; restore puts it back. */
    private fun takenFromCaption(caption: String): Long? {
        val match = Regex("Taken (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})").find(caption) ?: return null
        return try {
            LocalDateTime.parse(match.groupValues[1], DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    fun fileNameFor(m: TgMedia): String {
        if (m.fileName.isNotBlank()) return m.fileName
        val stamp = Fmt.stamp(m.date)
        return when (m.kind) {
            Kind.PHOTO -> "IMG_${stamp}_${m.messageId}.jpg"
            Kind.VIDEO, Kind.GIF -> "VID_${stamp}_${m.messageId}.mp4"
            Kind.AUDIO -> "${m.title.ifBlank { "Audio_$stamp" }}.mp3"
            Kind.VOICE -> "Voice_${stamp}_${m.messageId}.ogg"
            Kind.FILE -> "File_${stamp}_${m.messageId}"
        }
    }
}

/** Writes a file into Pictures, Movies, Music or Download under a CloudRoll folder. */
object MediaSaver {
    private fun clean(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "CloudRoll" }

    fun save(ctx: Context, src: File, displayName: String, mime: String, kind: Kind, folder: String, takenMillis: Long): Uri? {
        val (collection, base) = when {
            mime.startsWith("image/") || kind == Kind.PHOTO ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") || kind == Kind.VIDEO || kind == Kind.GIF ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
            mime.startsWith("audio/") || kind == Kind.AUDIO || kind == Kind.VOICE ->
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, clean(displayName))
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$base/CloudRoll/${clean(folder)}")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (base == Environment.DIRECTORY_PICTURES || base == Environment.DIRECTORY_MOVIES) {
                put(MediaStore.MediaColumns.DATE_TAKEN, takenMillis)
            }
        }
        val resolver = ctx.contentResolver
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out, 256 * 1024) } }
                ?: throw IllegalStateException("No output")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
