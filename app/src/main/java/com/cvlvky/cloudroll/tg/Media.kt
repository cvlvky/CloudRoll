package com.cvlvky.cloudroll.tg

import androidx.compose.runtime.Immutable
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageAnimation
import dev.g000sha256.tdl.dto.MessageAudio
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessagePhoto
import dev.g000sha256.tdl.dto.MessageVideo
import dev.g000sha256.tdl.dto.MessageVideoNote
import dev.g000sha256.tdl.dto.MessageVoiceNote
import dev.g000sha256.tdl.dto.Minithumbnail
import dev.g000sha256.tdl.dto.PhotoSize
import dev.g000sha256.tdl.dto.File as TdFile

enum class Kind { PHOTO, VIDEO, GIF, AUDIO, VOICE, FILE }

/** One photo, video, song, voice message or file in a Telegram chat. */
@Immutable
data class TgMedia(
    val chatId: Long,
    val messageId: Long,
    val date: Int,
    val kind: Kind,
    val file: TdFile,
    val thumb: TdFile?,
    val mini: Minithumbnail?,
    val width: Int,
    val height: Int,
    val duration: Int,
    val title: String,
    val performer: String,
    val fileName: String,
    val mime: String,
    val size: Long,
    val canSave: Boolean,
    val caption: String,
) {
    val key: String get() = "$chatId:$messageId"

    /** Plays as a video in the viewer (videos, GIFs, and video files sent as documents). */
    val isVideo: Boolean get() = kind == Kind.VIDEO || kind == Kind.GIF || (kind == Kind.FILE && mime.startsWith("video/"))

    /** Shows as a picture in the viewer (photos, and image files sent as documents). */
    val isImage: Boolean get() = kind == Kind.PHOTO || (kind == Kind.FILE && mime.startsWith("image/") && !mime.contains("svg"))

    /** Plays in the music player. */
    val isAudio: Boolean get() = kind == Kind.AUDIO || kind == Kind.VOICE || (kind == Kind.FILE && mime.startsWith("audio/"))

    val displayName: String
        get() = when {
            title.isNotBlank() -> title
            fileName.isNotBlank() -> fileName
            else -> when (kind) {
                Kind.PHOTO -> "Photo"
                Kind.VIDEO -> "Video"
                Kind.GIF -> "GIF"
                Kind.AUDIO -> "Audio"
                Kind.VOICE -> "Voice message"
                Kind.FILE -> "File"
            }
        }
}

private fun PhotoSize.area(): Long = width.toLong() * height.toLong()

private fun sizeOf(f: TdFile): Long = if (f.size > 0) f.size else f.expectedSize

/** Turns a Telegram message into a [TgMedia], or null when it carries none. */
fun Message.toMedia(): TgMedia? {
    val save = canBeSaved
    return when (val c = content) {
        is MessagePhoto -> {
            val sizes = c.photo.sizes
            val biggest = sizes.maxByOrNull { it.area() } ?: return null
            val thumb = sizes.filter { it.width >= 160 || it.height >= 160 }.minByOrNull { it.area() } ?: biggest
            TgMedia(
                chatId, id, date, Kind.PHOTO, biggest.photo, thumb.photo, c.photo.minithumbnail,
                biggest.width, biggest.height, 0, "", "", "", "image/jpeg", sizeOf(biggest.photo), save, c.caption.text,
            )
        }
        is MessageVideo -> {
            val v = c.video
            TgMedia(
                chatId, id, date, Kind.VIDEO, v.video, v.thumbnail?.file, v.minithumbnail,
                v.width, v.height, v.duration, "", "", v.fileName, v.mimeType.ifBlank { "video/mp4" }, sizeOf(v.video), save, c.caption.text,
            )
        }
        is MessageAnimation -> {
            val a = c.animation
            TgMedia(
                chatId, id, date, Kind.GIF, a.animation, a.thumbnail?.file, a.minithumbnail,
                a.width, a.height, a.duration, "", "", a.fileName, a.mimeType.ifBlank { "video/mp4" }, sizeOf(a.animation), save, c.caption.text,
            )
        }
        is MessageVideoNote -> {
            val v = c.videoNote
            TgMedia(
                chatId, id, date, Kind.VIDEO, v.video, v.thumbnail?.file, v.minithumbnail,
                v.length, v.length, v.duration, "Video message", "", "", "video/mp4", sizeOf(v.video), save, "",
            )
        }
        is MessageAudio -> {
            val a = c.audio
            val title = a.title.ifBlank { a.fileName.substringBeforeLast('.', a.fileName) }
            TgMedia(
                chatId, id, date, Kind.AUDIO, a.audio, a.albumCoverThumbnail?.file, a.albumCoverMinithumbnail,
                0, 0, a.duration, title, a.performer, a.fileName, a.mimeType.ifBlank { "audio/mpeg" }, sizeOf(a.audio), save, c.caption.text,
            )
        }
        is MessageVoiceNote -> {
            val v = c.voiceNote
            TgMedia(
                chatId, id, date, Kind.VOICE, v.voice, null, null,
                0, 0, v.duration, "Voice message", "", "", v.mimeType.ifBlank { "audio/ogg" }, sizeOf(v.voice), save, c.caption.text,
            )
        }
        is MessageDocument -> {
            val d = c.document
            TgMedia(
                chatId, id, date, Kind.FILE, d.document, d.thumbnail?.file, d.minithumbnail,
                0, 0, 0, "", "", d.fileName, d.mimeType.ifBlank { guessMime(d.fileName) }, sizeOf(d.document), save, c.caption.text,
            )
        }
        else -> null
    }
}

fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mkv" -> "video/x-matroska"
    "mp4", "m4v" -> "video/mp4"
    "mov" -> "video/quicktime"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    "ts", "m2ts" -> "video/mp2t"
    "3gp" -> "video/3gpp"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "heic", "heif" -> "image/heic"
    "gif" -> "image/gif"
    "mp3" -> "audio/mpeg"
    "m4a", "aac" -> "audio/mp4"
    "ogg", "oga", "opus" -> "audio/ogg"
    "flac" -> "audio/flac"
    "wav" -> "audio/wav"
    "pdf" -> "application/pdf"
    "zip" -> "application/zip"
    "apk" -> "application/vnd.android.package-archive"
    else -> "application/octet-stream"
}
