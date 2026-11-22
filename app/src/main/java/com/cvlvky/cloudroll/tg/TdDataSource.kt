package com.cvlvky.cloudroll.tg

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import dev.g000sha256.tdl.dto.File as TdFile

/** `tdfile://<fileId>`: a Telegram file the player reads while TDLib downloads it. */
fun tdFileUri(fileId: Int): Uri = Uri.Builder().scheme("tdfile").authority(fileId.toString()).build()

fun fileIdOf(uri: Uri): Int? = uri.authority?.toIntOrNull()

/**
 * Lets the player start a Telegram video or song before it has downloaded.
 *
 * Each read asks TDLib for the bytes at that exact place in the file. TDLib downloads a window
 * from there, so seeking moves the download instead of waiting for everything before it, and
 * stopping early downloads nothing more than the window ahead.
 */
@UnstableApi
class TdDataSource : BaseDataSource(true) {

    private var uri: Uri? = null
    private var fileId = 0
    private var size = 0L
    private var position = 0L
    private var remaining = 0L
    private var opened = false
    private var file: TdFile? = null
    private var raf: RandomAccessFile? = null
    private var rafPath: String? = null
    private var lastRequestAt = -1L
    private var lastRequestTime = 0L

    override fun getUri(): Uri? = uri

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        fileId = fileIdOf(dataSpec.uri) ?: throw IOException("Not a Telegram file: ${dataSpec.uri}")
        position = dataSpec.position
        val client = Tg.client ?: throw IOException("Not connected to Telegram")
        val f = blocking(OPEN_TIMEOUT_MS) { client.getFile(fileId = fileId).orThrow() }
        Tg.fileFlow(fileId).value = f
        file = f
        size = if (f.size > 0) f.size else f.expectedSize
        if (size <= 0L) throw IOException("Telegram did not say how big this file is")
        if (position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, size - position) else size - position
        if (!f.local.isDownloadingCompleted && available(f, position) == 0L) request(position)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        repeat(3) {
            val avail = awaitBytes()
            val want = minOf(length.toLong(), remaining, avail).toInt()
            val f = file ?: throw IOException("File state lost")
            val n = try {
                val r = openFile(f.local.path)
                r.seek(position)
                r.read(buffer, offset, want)
            } catch (e: IOException) {
                -1
            }
            if (n > 0) {
                position += n
                remaining -= n
                bytesTransferred(n)
                return n
            }
            // TDLib may have moved the file when it finished: read its state again and retry.
            closeFile()
            val client = Tg.client ?: throw IOException("Not connected to Telegram")
            file = blocking(OPEN_TIMEOUT_MS) { client.getFile(fileId = fileId).orThrow() }
        }
        throw IOException("Could not read the downloaded part of this file")
    }

    /** Bytes ready on the phone at the current position, waiting for TDLib when there are none. */
    private fun awaitBytes(): Long {
        var stalledSince = System.currentTimeMillis()
        while (true) {
            val f = file ?: throw IOException("File state lost")
            val ready = available(f, position)
            if (ready > 0L) {
                // Keep the window ahead of the player: ask for more before it runs dry.
                if (!f.local.isDownloadingCompleted && !f.local.isDownloadingActive && ready < LOW_WATER) request(position)
                return ready
            }
            val l = f.local
            val start = l.downloadOffset
            val end = start + l.downloadedPrefixSize
            val outside = position < start || position > end
            if (!l.isDownloadingActive || outside) request(position)
            val flow = Tg.fileFlow(fileId)
            val next = blocking(READ_TIMEOUT_MS + 5_000) {
                withTimeoutOrNull(READ_TIMEOUT_MS) { flow.first { it != null && it != f } }
            }
            if (next != null) {
                if (available(next, position) > 0L || next.local.downloadedPrefixSize != l.downloadedPrefixSize) {
                    stalledSince = System.currentTimeMillis()
                }
                file = next
            }
            if (System.currentTimeMillis() - stalledSince > STALL_MS) {
                throw IOException("Telegram stopped sending this file")
            }
        }
    }

    private fun available(f: TdFile, pos: Long): Long {
        val l = f.local
        if (l.path.isEmpty()) return 0L
        if (l.isDownloadingCompleted) return (size - pos).coerceAtLeast(0L)
        val start = l.downloadOffset
        val end = start + l.downloadedPrefixSize
        return if (pos in start until end) end - pos else 0L
    }

    /** Points TDLib's download at [pos], at most once a second for the same place. */
    private fun request(pos: Long) {
        val now = System.currentTimeMillis()
        if (pos == lastRequestAt && now - lastRequestTime < 1_000) return
        lastRequestAt = pos
        lastRequestTime = now
        val client = Tg.client ?: throw IOException("Not connected to Telegram")
        val f = blocking(OPEN_TIMEOUT_MS) {
            client.downloadFile(fileId = fileId, priority = 32, offset = pos, limit = WINDOW, synchronous = false).orNull()
        }
        if (f != null) {
            file = f
            Tg.fileFlow(fileId).value = f
        }
    }

    private fun openFile(path: String): RandomAccessFile {
        val existing = raf
        if (existing != null && rafPath == path) return existing
        closeFile()
        if (path.isEmpty()) throw IOException("No local file yet")
        return RandomAccessFile(path, "r").also {
            raf = it
            rafPath = path
        }
    }

    private fun closeFile() {
        try {
            raf?.close()
        } catch (_: IOException) {
        }
        raf = null
        rafPath = null
    }

    override fun close() {
        closeFile()
        uri = null
        file = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /** Media3 reads on its own loading thread and cancels by interrupting it. */
    private fun <T> blocking(timeoutMs: Long, block: suspend () -> T): T = try {
        runBlocking { withTimeout(timeoutMs) { block() } }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Cancelled")
    } catch (e: TimeoutCancellationException) {
        throw IOException("Telegram did not answer in time", e)
    } catch (e: TgError) {
        throw IOException(e.message, e)
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = TdDataSource()
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 60_000L
        const val READ_TIMEOUT_MS = 15_000L
        const val STALL_MS = 90_000L
        const val WINDOW = 48L * 1024 * 1024
        const val LOW_WATER = 8L * 1024 * 1024
    }
}
