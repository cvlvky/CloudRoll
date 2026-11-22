package com.cvlvky.cloudroll.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.cvlvky.cloudroll.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A newer CloudRoll on GitHub. */
data class Release(val build: Int, val version: String, val notes: List<String>, val url: String, val size: Long, val sha256: String?)

/**
 * In-app updates: every time the app opens it reads this repository's GitHub releases, and when a
 * newer build is out it offers it. The APK downloads inside the app and Android installs it over
 * the old version, keeping the sign-in and settings.
 */
object Updater {
    val available = MutableStateFlow<Release?>(null)
    val progress = MutableStateFlow<Int?>(null)
    val error = MutableStateFlow<String?>(null)

    /** Set when the user closed the pop-up, so it does not come back until the app opens again. */
    val dismissed = MutableStateFlow(false)

    @Volatile
    var lastCheck = 0L
        private set

    /** Asks GitHub for the newest release. Null when there is none newer, or GitHub could not be reached. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        lastCheck = System.currentTimeMillis()
        val c = (URL("https://api.github.com/repos/${BuildConfig.REPO}/releases?per_page=15").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CloudRoll/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (c.responseCode != 200) return@withContext null
            val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            var best: Release? = null
            for (i in 0 until arr.length()) {
                val rel = arr.getJSONObject(i)
                if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) continue
                val m = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)$").find(rel.optString("tag_name")) ?: continue
                val build = m.groupValues[3].toInt()
                val assets = rel.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    val name = a.optString("name")
                    if (!name.startsWith("CloudRoll", ignoreCase = true) || !name.endsWith(".apk")) continue
                    if (best == null || build > best.build) {
                        best = Release(
                            build = build,
                            version = "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}",
                            notes = notesOf(rel.optString("body")),
                            url = a.optString("browser_download_url"),
                            size = a.optLong("size"),
                            sha256 = a.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 },
                        )
                    }
                }
            }
            val newer = best?.takeIf { it.build > BuildConfig.VERSION_CODE }
            available.value = newer
            newer
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    /** The "What's new" lines of the release notes, without the install steps. */
    private fun notesOf(body: String): List<String> {
        val out = ArrayList<String>()
        var skip = false
        for (raw in body.lines()) {
            val l = raw.replace("**", "").replace("`", "").trim()
            if (l.startsWith("#")) {
                skip = l.contains("install", ignoreCase = true)
                continue
            }
            if (skip || l.isBlank()) continue
            if (Regex("^(co-authored-by|claude-session|signed-off-by)", RegexOption.IGNORE_CASE).containsMatchIn(l)) continue
            out.add(l.removePrefix("- ").removePrefix("* "))
        }
        return out.take(10)
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }

    /** Downloads the APK, checking it against GitHub's checksum when there is one. */
    suspend fun download(ctx: Context, rel: Release): File? = withContext(Dispatchers.IO) {
        error.value = null
        progress.value = 0
        dir(ctx).listFiles()?.forEach { it.delete() }
        val out = File(dir(ctx), "CloudRoll-${rel.version}.apk")
        var link = rel.url
        var conn: HttpURLConnection? = null
        try {
            for (hop in 0 until 6) {
                val c = URL(link).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 20_000
                c.readTimeout = 30_000
                c.setRequestProperty("Accept", "application/octet-stream")
                c.setRequestProperty("User-Agent", "CloudRoll/${BuildConfig.VERSION_NAME}")
                val code = c.responseCode
                val location = c.getHeaderField("Location")
                if (code in 300..399 && location != null) {
                    link = URL(URL(link), location).toString()
                    c.disconnect()
                    continue
                }
                if (code != 200) throw IllegalStateException("GitHub answered $code")
                conn = c
                break
            }
            val c = conn ?: throw IllegalStateException("Too many redirects")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: rel.size
            val digest = MessageDigest.getInstance("SHA-256")
            var got = 0L
            c.inputStream.use { input ->
                FileOutputStream(out).use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        os.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        got += n
                        if (total > 0) progress.value = (got * 100 / total).toInt().coerceIn(0, 100)
                    }
                }
            }
            if (total > 0 && got < total) throw IllegalStateException("The download stopped early")
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (rel.sha256 != null && !rel.sha256.equals(hash, ignoreCase = true)) {
                out.delete()
                throw IllegalStateException("The download was damaged")
            }
            progress.value = 100
            out
        } catch (e: Exception) {
            error.value = e.message ?: "Download failed"
            progress.value = null
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** True when Android may install apps from CloudRoll. */
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Opens the one-time "Allow from this source" screen. */
    fun openInstallPermission(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    /** Hands the APK to Android's installer. False when the install permission is still needed. */
    fun install(ctx: Context, apk: File): Boolean {
        if (!canInstall(ctx)) {
            openInstallPermission(ctx)
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return true
    }

    /** The APK already downloaded for [rel], if any. */
    fun downloaded(ctx: Context, rel: Release): File? =
        File(dir(ctx), "CloudRoll-${rel.version}.apk").takeIf { it.exists() && it.length() > 0 && progress.value == 100 }
}
