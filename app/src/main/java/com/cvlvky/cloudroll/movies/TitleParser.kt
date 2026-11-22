package com.cvlvky.cloudroll.movies

import java.util.Locale

/** What a video's file name says: the film, its year, an episode, the quality. */
data class ParsedName(
    val title: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val quality: String?,
) {
    val isEpisode: Boolean get() = season != null || episode != null

    /** The key a match is remembered by: the cleaned title and the year. */
    val key: String get() = TitleParser.norm(title) + "|" + (year ?: 0)
}

/**
 * Cleans names like "@TamilChannel - Leo.2023.1080p.WEB-DL.Tamil.HEVC.mkv" into "Leo", 2023, 1080p.
 */
object TitleParser {

    private val extension = Regex("\\.(mkv|mp4|m4v|avi|mov|webm|ts|m2ts|wmv|flv|3gp|mpg|mpeg)$", RegexOption.IGNORE_CASE)

    /** Words that mark the end of the title: quality, source, codecs, audio, languages, site tags. */
    private val stopWords = setOf(
        "2160p", "1080p", "720p", "576p", "480p", "360p", "240p", "4k", "uhd", "fhd", "hd", "hdr", "hdr10", "sdr", "dv",
        "web", "webdl", "web-dl", "webrip", "web-rip", "hdrip", "brrip", "bdrip", "bluray", "blu-ray", "dvdrip", "dvdscr", "hdtv",
        "predvd", "pre-dvd", "cam", "camrip", "hdcam", "hdtc", "tc", "ts", "hq", "true", "proper", "repack", "uncut", "extended",
        "remastered", "imax", "x264", "x265", "h264", "h265", "h", "hevc", "avc", "10bit", "8bit", "aac", "aac2", "ac3", "eac3",
        "dd", "dd5", "ddp", "ddp5", "atmos", "dts", "truehd", "opus", "esub", "esubs", "msub", "msubs", "sub", "subs", "subbed",
        "dual", "multi", "audio", "org", "original", "line", "tamil", "malayalam", "telugu", "hindi", "kannada", "english",
        "tam", "mal", "tel", "hin", "kan", "eng", "dubbed", "hq-rip", "amzn", "nf", "hotstar", "jiohotstar", "sunnxt", "zee5",
        "sony", "sonyliv", "aha", "manorama", "prime", "netflix", "full", "movie", "film", "sample", "rip", "untouched",
    )

    private val leadingTags = Regex(
        "^\\s*(?:@[\\w.]+|\\[[^\\]]*\\]|\\([^)]*\\)|\\{[^}]*\\}|www\\.\\S+|\\S+\\.(?:com|net|org|xyz|in|me|co|io|club|tv|site|today|link|lol|fun|bar|vip|li|cc|art|top|pics|mov|life))\\s*[-_.:|~]*\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val episode = Regex("\\bS(\\d{1,2})\\s*[ .-]?\\s*E[Pp]?(\\d{1,3})\\b|\\bSeason\\s*(\\d{1,2})\\b|\\bEp(?:isode)?\\s*(\\d{1,3})\\b", RegexOption.IGNORE_CASE)
    private val quality = Regex("\\b(2160p|1080p|720p|576p|480p|360p|4K)\\b", RegexOption.IGNORE_CASE)
    private val size = Regex("\\b\\d+(?:\\.\\d+)?\\s?(?:GB|MB)\\b", RegexOption.IGNORE_CASE)

    fun parse(raw: String): ParsedName? {
        var s = raw.trim().replace(extension, "")
        if (s.isBlank()) return null
        // The first line of a caption is usually the title.
        s = s.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        repeat(4) { s = s.replace(leadingTags, "") }
        s = s.replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim()
        val q = quality.find(s)?.value?.lowercase(Locale.ROOT)?.let { if (it == "4k") "2160p" else it }
        val ep = episode.find(s)
        val season = ep?.let { it.groupValues[1].toIntOrNull() ?: it.groupValues[3].toIntOrNull() }
        val epNo = ep?.let { it.groupValues[2].toIntOrNull() ?: it.groupValues[4].toIntOrNull() }

        // A year is 1920 to next year, not at the very start (a film can be called "2018").
        val nextYear = java.time.LocalDate.now().year + 1
        val yearMatch = Regex("(?<=[\\s(\\[-])((?:19[2-9]|20[0-9])\\d)(?=[\\s)\\]-]|$)").findAll(s)
            .firstOrNull { m -> m.range.first > 0 && (m.value.toIntOrNull() ?: 0) in 1920..nextYear }
        val year = yearMatch?.value?.toIntOrNull()

        var cut = s.length
        yearMatch?.let { cut = minOf(cut, it.range.first) }
        ep?.let { cut = minOf(cut, it.range.first) }
        size.find(s)?.let { cut = minOf(cut, it.range.first) }
        // The first stop word ends the title.
        var pos = 0
        for (word in s.split(' ')) {
            val w = word.lowercase(Locale.ROOT).trim('(', ')', '[', ']', '-', '+', ',')
            if (pos > 0 && (w in stopWords || quality.matches(w))) {
                cut = minOf(cut, pos)
                break
            }
            pos += word.length + 1
        }
        var title = s.substring(0, cut.coerceIn(0, s.length))
            .replace(Regex("[\\[\\](){}]"), " ")
            .replace(Regex("\\s+-\\s*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ':', '~', '+')
        if (title.isBlank() && year != null) title = year.toString()
        if (title.length < 2 || title.count { it.isLetterOrDigit() } < 2) return null
        return ParsedName(title, year, season, epNo, q)
    }

    /** Lower case letters and digits only, for comparing titles. */
    fun norm(t: String): String =
        java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\b(the|a|an)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** How alike two titles are, from 0 to 1. */
    fun similarity(a: String, b: String): Double {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.replace(" ", "") == y.replace(" ", "")) return 0.97
        val ta = x.split(' ').toSet()
        val tb = y.split(' ').toSet()
        val inter = ta.intersect(tb).size.toDouble()
        val jaccard = inter / ta.union(tb).size
        val contained = if (ta.containsAll(tb) || tb.containsAll(ta)) 0.85 else 0.0
        return maxOf(jaccard, contained * minOf(ta.size, tb.size) / maxOf(ta.size, tb.size).toDouble() + 0.1).coerceAtMost(0.95)
    }

    /** "2160p" first, for picking the best copy. */
    fun qualityRank(q: String?): Int = when (q) {
        "2160p" -> 5
        "1080p" -> 4
        "720p" -> 3
        "576p", "480p" -> 2
        "360p", "240p" -> 1
        else -> 0
    }
}
