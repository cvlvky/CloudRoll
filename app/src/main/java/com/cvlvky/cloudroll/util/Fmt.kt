package com.cvlvky.cloudroll.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Sizes, durations and dates written the way people read them. */
object Fmt {
    fun size(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    fun duration(seconds: Int): String {
        if (seconds <= 0) return "0:00"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun durationMs(ms: Long): String = duration((ms / 1000).toInt())

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** "Today", "Yesterday", "Sunday, 16 Nov" or "16 Nov 2025" for a Telegram date (seconds). */
    fun day(unixSeconds: Int): String {
        val d = Instant.ofEpochSecond(unixSeconds.toLong()).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when {
            d == today -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            d.isAfter(today.minusDays(7)) -> d.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault()))
            d.year == today.year -> d.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
            else -> d.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
        }
    }

    fun dayKey(unixSeconds: Int): Long =
        Instant.ofEpochSecond(unixSeconds.toLong()).atZone(zone).toLocalDate().toEpochDay()

    fun dateTime(unixSeconds: Int): String =
        Instant.ofEpochSecond(unixSeconds.toLong()).atZone(zone)
            .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault()))

    fun stamp(unixSeconds: Int): String =
        Instant.ofEpochSecond(unixSeconds.toLong()).atZone(zone)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US))

    fun ago(millis: Long): String {
        if (millis <= 0) return "never"
        val mins = (System.currentTimeMillis() - millis) / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 24 * 60 -> "${mins / 60} h ago"
            else -> "${mins / (24 * 60)} days ago"
        }
    }
}
