package com.cvlvky.cloudroll.backup

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

/** A folder of photos and videos on the phone, as the gallery groups them. */
data class Bucket(val id: String, val name: String, val count: Int)

/** One photo or video on the phone. */
data class LocalItem(
    val id: Long,
    val uri: Uri,
    val path: String?,
    val name: String,
    val size: Long,
    val dateTaken: Long,
    val dateModified: Long,
    val mime: String,
    val isVideo: Boolean,
    val bucketId: String,
    val bucketName: String,
) {
    /** Changes when the file is edited, so an edited photo is backed up again. */
    val key: String get() = "${if (isVideo) "v" else "i"}:$id:$size:$dateModified"
}

data class Thumb(val file: File, val width: Int, val height: Int)

/** Reads the phone's photos and videos through Android's media store. */
object LocalMedia {

    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    @Suppress("DEPRECATION")
    private val DATA_COLUMN = MediaStore.MediaColumns.DATA

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** True when CloudRoll may read every photo and video. */
    fun hasFullAccess(ctx: Context): Boolean = permissions().all { granted(ctx, it) }

    /** True when the phone only shares the photos picked by hand (Android 14 and newer). */
    fun hasPartialAccess(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 && !hasFullAccess(ctx) && granted(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    fun hasAccess(ctx: Context) = hasFullAccess(ctx) || hasPartialAccess(ctx)

    private val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        DATA_COLUMN,
    )

    private fun collections(includeVideos: Boolean): List<Pair<Uri, Boolean>> {
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) to false
        val videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) to true
        return if (includeVideos) listOf(images, videos) else listOf(images)
    }

    private fun query(ctx: Context, includeVideos: Boolean, bucketIds: Set<String>?): List<LocalItem> {
        val out = ArrayList<LocalItem>()
        for ((collection, isVideo) in collections(includeVideos)) {
            var selection = "${MediaStore.MediaColumns.SIZE} > 0"
            var args: Array<String>? = null
            if (bucketIds != null) {
                if (bucketIds.isEmpty()) continue
                selection += " AND ${MediaStore.MediaColumns.BUCKET_ID} IN (${bucketIds.joinToString(",") { "?" }})"
                args = bucketIds.toTypedArray()
            }
            val cursor = try {
                ctx.contentResolver.query(collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} ASC")
            } catch (e: Exception) {
                null
            } ?: continue
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val modified = c.getLong(3)
                    val taken = if (c.isNull(4)) modified * 1000 else c.getLong(4)
                    out.add(
                        LocalItem(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            path = c.getString(8),
                            name = c.getString(1) ?: "media_$id",
                            size = c.getLong(2),
                            dateTaken = if (taken > 0) taken else modified * 1000,
                            dateModified = modified,
                            mime = c.getString(5) ?: if (isVideo) "video/mp4" else "image/jpeg",
                            isVideo = isVideo,
                            bucketId = c.getString(6) ?: "",
                            bucketName = c.getString(7) ?: "Other",
                        ),
                    )
                }
            }
        }
        return out
    }

    /** Every folder with photos or videos, biggest first. */
    fun buckets(ctx: Context): List<Bucket> =
        query(ctx, includeVideos = true, bucketIds = null)
            .groupBy { it.bucketId }
            .map { (id, items) -> Bucket(id, items.first().bucketName, items.size) }
            .sortedByDescending { it.count }

    /** The folders to back up: the chosen ones, or the camera folder when none were chosen. */
    fun chosenBuckets(ctx: Context, chosen: Set<String>): Set<String> {
        if (chosen.isNotEmpty()) return chosen
        val all = buckets(ctx)
        val camera = all.filter { it.name.equals("Camera", ignoreCase = true) }.map { it.id }.toSet()
        return camera.ifEmpty { all.take(1).map { it.id }.toSet() }
    }

    /** The photos (and videos) of [bucketIds], oldest first. */
    fun items(ctx: Context, bucketIds: Set<String>, includeVideos: Boolean): List<LocalItem> =
        query(ctx, includeVideos, bucketIds).sortedBy { it.dateTaken }

    fun exists(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        false
    }

    /** A small JPEG preview (at most 320 pixels a side) for the Telegram message. */
    fun thumbnail(ctx: Context, item: LocalItem): Thumb? = try {
        val bmp = ctx.contentResolver.loadThumbnail(item.uri, Size(320, 320), null)
        val scale = minOf(1f, 320f / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            bmp
        }
        val dir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
        val f = File(dir, "${item.id}.jpg")
        FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        Thumb(f, scaled.width, scaled.height)
    } catch (e: Exception) {
        null
    }

    /** Copies a file the app cannot read by path into its own cache, for TDLib to upload. */
    fun copyToCache(ctx: Context, item: LocalItem): File? = try {
        val dir = File(ctx.cacheDir, "upload").apply { mkdirs() }
        val f = File(dir, "${item.id}_${item.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        ctx.contentResolver.openInputStream(item.uri)?.use { input ->
            FileOutputStream(f).use { input.copyTo(it, 256 * 1024) }
        } ?: return null
        f
    } catch (e: Exception) {
        null
    }
}
