package com.cvlvky.cloudroll.backup

import android.content.Context
import android.net.Uri
import com.cvlvky.cloudroll.data.Db
import com.cvlvky.cloudroll.data.Prefs
import com.cvlvky.cloudroll.tg.Tg
import com.cvlvky.cloudroll.tg.orNull
import com.cvlvky.cloudroll.util.Fmt
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.InputDocument
import dev.g000sha256.tdl.dto.InputFileLocal
import dev.g000sha256.tdl.dto.InputMessageDocument
import dev.g000sha256.tdl.dto.InputThumbnail
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.TextEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backs up the phone's photos and videos to a private Telegram channel, "CloudRoll Backup",
 * as original files (no compression). Each upload is remembered, so nothing goes up twice.
 */
object BackupEngine {

    data class State(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val uploaded: Int = 0,
        val failed: Int = 0,
        val currentName: String = "",
        val currentPct: Int = 0,
        val message: String = "",
    )

    val state = MutableStateFlow(State())

    private val mutex = Mutex()

    @Volatile
    private var stopRequested = false

    /** Background runs leave big videos for a backup started from the app, which can take its time. */
    private const val BACKGROUND_MAX_BYTES = 300L * 1024 * 1024

    fun stop() {
        stopRequested = true
    }

    /** How many photos and videos are waiting to be backed up. */
    fun pendingCount(ctx: Context): Int {
        if (!LocalMedia.hasAccess(ctx)) return 0
        val folders = LocalMedia.chosenBuckets(ctx, Prefs.backupFolders.value)
        val done = Db.backedUpKeys(Prefs.backupChatId.value)
        return LocalMedia.items(ctx, folders, Prefs.backupVideos.value).count { it.key !in done }
    }

    /** Runs one backup pass. [deadline] stops it between files (background jobs have a time limit). */
    suspend fun run(ctx: Context, background: Boolean, deadline: Long = Long.MAX_VALUE) {
        if (!mutex.tryLock()) return
        stopRequested = false
        try {
            state.value = State(running = true, message = "Getting ready…")
            val c = Tg.client
            if (c == null || !Tg.awaitReady(30_000)) {
                state.value = State(message = "Sign in to Telegram first.")
                return
            }
            if (!LocalMedia.hasAccess(ctx)) {
                state.value = State(message = "Allow CloudRoll to see your photos and videos first.")
                return
            }
            val chatId = ensureChannel(c)
            if (chatId == null) {
                state.value = State(message = "Could not create the backup channel. Check the connection and try again.")
                return
            }
            val folders = LocalMedia.chosenBuckets(ctx, Prefs.backupFolders.value)
            val done = Db.backedUpKeys(chatId)
            val items = LocalMedia.items(ctx, folders, Prefs.backupVideos.value)
                .filter { it.key !in done }
                .filter { !background || it.size <= BACKGROUND_MAX_BYTES }
            if (items.isEmpty()) {
                Prefs.backupLastRun.value = System.currentTimeMillis()
                state.value = State(message = "Everything is backed up.")
                return
            }
            state.value = State(running = true, total = items.size, message = "Backing up")
            var uploaded = 0
            var failed = 0
            for ((i, item) in items.withIndex()) {
                if (stopRequested || System.currentTimeMillis() > deadline) break
                state.update { it.copy(done = i, currentName = item.name, currentPct = 0) }
                when (uploadOne(ctx, c, chatId, item)) {
                    Outcome.OK -> uploaded++
                    Outcome.FAILED -> failed++
                    Outcome.CHANNEL_GONE -> {
                        state.value = State(message = "The backup channel is gone. Tap Back up now to make a new one.")
                        return
                    }
                }
                state.update { it.copy(uploaded = uploaded, failed = failed) }
            }
            Prefs.backupLastRun.value = System.currentTimeMillis()
            val left = items.size - uploaded - failed
            val msg = buildString {
                append(if (uploaded == 1) "1 item backed up" else "$uploaded items backed up")
                if (failed > 0) append(", $failed failed")
                if (left > 0) append(", $left still waiting")
                append('.')
            }
            state.value = State(uploaded = uploaded, failed = failed, total = items.size, done = uploaded + failed, message = msg)
        } finally {
            state.update { it.copy(running = false) }
            mutex.unlock()
        }
    }

    private enum class Outcome { OK, FAILED, CHANNEL_GONE }

    /** The backup channel's chat id, making the channel the first time. */
    private suspend fun ensureChannel(c: TdlClient): Long? {
        val id = Prefs.backupChatId.value
        if (id != 0L && c.getChat(chatId = id) is TdlResult.Success) return id
        if (id != 0L) Db.clearBackups()
        val chat = c.createNewSupergroupChat(
            title = "CloudRoll Backup",
            isForum = false,
            isChannel = true,
            description = "Private backup of my phone's photos and videos, made by the CloudRoll app.",
            location = null,
            messageAutoDeleteTime = 0,
            forImport = false,
        ).orNull() ?: return null
        Prefs.backupChatId.value = chat.id
        return chat.id
    }

    private fun caption(item: LocalItem): String {
        val taken = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(item.dateTaken))
        val icon = if (item.isVideo) "🎬" else "📷"
        return "$icon ${item.name}\nTaken $taken · ${item.bucketName} · ${Fmt.size(item.size)}"
    }

    private fun floodWait(message: String): Int? =
        Regex("(?:FLOOD_WAIT_|retry after )(\\d+)", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)?.toIntOrNull()

    private suspend fun uploadOne(ctx: Context, c: TdlClient, chatId: Long, item: LocalItem): Outcome {
        var temp: File? = null
        val thumb = LocalMedia.thumbnail(ctx, item)
        try {
            val direct = item.path?.takeIf { File(it).canRead() }
            val path = direct ?: LocalMedia.copyToCache(ctx, item)?.also { temp = it }?.path ?: return Outcome.FAILED
            val content = InputMessageDocument(
                document = InputDocument(
                    document = InputFileLocal(path = path),
                    thumbnail = thumb?.let { InputThumbnail(thumbnail = InputFileLocal(path = it.file.path), width = it.width, height = it.height) },
                    disableContentTypeDetection = true,
                ),
                caption = FormattedText(text = caption(item), entities = emptyArray<TextEntity>()),
            )
            var attempts = 0
            while (true) {
                val sent = c.sendMessage(chatId = chatId, inputMessageContent = content)
                if (sent is TdlResult.Failure) {
                    if (isChannelGone(sent.message)) return goneChannel()
                    val wait = floodWait(sent.message)
                    if (wait != null && attempts++ < 3) {
                        delay(wait * 1000L + 1000L)
                        continue
                    }
                    return Outcome.FAILED
                }
                val message = (sent as TdlResult.Success).result
                val tempId = message.id
                val fileId = (message.content as? MessageDocument)?.document?.document?.id
                val result = try {
                    coroutineScope {
                        val watcher = fileId?.let { id ->
                            launch {
                                Tg.fileFlow(id).collect { f ->
                                    if (f != null) {
                                        val total = if (f.size > 0) f.size else f.expectedSize
                                        if (total > 0) {
                                            val pct = (f.remote.uploadedSize * 100 / total).toInt().coerceIn(0, 100)
                                            state.update { it.copy(currentPct = pct) }
                                        }
                                    }
                                }
                            }
                        }
                        try {
                            withTimeoutOrNull(300_000L + item.size / 40L) {
                                Tg.sendResults.first { it.oldMessageId == tempId }
                            }
                        } finally {
                            watcher?.cancel()
                        }
                    }
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { c.deleteMessages(chatId = chatId, messageIds = longArrayOf(tempId), revoke = true) }
                    throw e
                }
                if (result == null) {
                    c.deleteMessages(chatId = chatId, messageIds = longArrayOf(tempId), revoke = true)
                    return Outcome.FAILED
                }
                if (result.error != null) {
                    if (isChannelGone(result.error)) return goneChannel()
                    val wait = floodWait(result.error)
                    if (wait != null && attempts++ < 3) {
                        delay(wait * 1000L + 1000L)
                        continue
                    }
                    return Outcome.FAILED
                }
                Db.markBackedUp(item.key, item.id, item.uri.toString(), item.size, chatId, result.newMessageId)
                // A steady pace keeps Telegram from asking to slow down on a big first backup.
                delay(1_500)
                return Outcome.OK
            }
        } finally {
            temp?.delete()
            thumb?.file?.delete()
        }
    }

    private fun isChannelGone(message: String): Boolean {
        val m = message.uppercase(Locale.ROOT)
        return "CHANNEL_INVALID" in m || "CHANNEL_PRIVATE" in m || "CHAT_NOT_FOUND" in m || "CHAT NOT FOUND" in m || "CHAT_WRITE_FORBIDDEN" in m
    }

    private fun goneChannel(): Outcome {
        Prefs.backupChatId.value = 0L
        Db.clearBackups()
        return Outcome.CHANNEL_GONE
    }

    /** Photos and videos already in the backup that are still taking space on the phone. */
    fun freeable(ctx: Context): List<Pair<Uri, Long>> {
        val chatId = Prefs.backupChatId.value
        if (chatId == 0L) return emptyList()
        return Db.backedUp(chatId).mapNotNull { b ->
            val uri = Uri.parse(b.uri)
            if (LocalMedia.exists(ctx, uri)) uri to b.size else null
        }
    }
}
