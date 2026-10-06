package com.arnav.music.domain.format

object Formatters {
    fun duration(ms: Long?): String {
        if (ms == null || ms < 0) return "–:––"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun longDuration(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "$h hr $m min"
            h > 0 -> "$h hr"
            else -> "$m min"
        }
    }

    fun compactCount(n: Long): String = when {
        n >= 1_000_000_000 -> "%.1fB".format(n / 1e9).replace(".0B", "B")
        n >= 1_000_000 -> "%.1fM".format(n / 1e6).replace(".0M", "M")
        n >= 1_000 -> "%.1fK".format(n / 1e3).replace(".0K", "K")
        else -> n.toString()
    }

    fun relative(then: Long, now: Long): String {
        val d = (now - then).coerceAtLeast(0)
        val min = d / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} hr ago"
            min < 2 * 24 * 60 -> "yesterday"
            min < 30 * 24 * 60 -> "${min / (24 * 60)} days ago"
            min < 365L * 24 * 60 -> "${min / (30 * 24 * 60)} mo ago"
            else -> "${min / (365L * 24 * 60)} yr ago"
        }
    }

    /** ISO-8601 duration from YouTube ("PT4M13S") → ms. */
    fun parseIsoDuration(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        val m = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").matchEntire(iso.trim()) ?: return null
        val (d, h, mi, s) = m.destructured
        val total = (d.toLongOrNull() ?: 0) * 86400 + (h.toLongOrNull() ?: 0) * 3600 + (mi.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
        return if (total == 0L) null else total * 1000
    }

    fun greeting(hour: Int): String = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Late night"
    }

    private val bracketNoise = Regex(
        """\s*[(\[](official|lyric|lyrics|lyrical|audio|video|music video|visualizer|hd|4k|mv|official audio|official music video|official video|full video|full song|video song|audio song)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val segmentSeparators = Regex("""\s+(?:\|\|?|‖|•)\s+""")
    /** " I " is used as a separator by many label channels — only trusted when it repeats. */
    private val capitalISeparator = Regex("""\s+I\s+""")
    private val trailingNoise = Regex(
        """\s*[-–:]?\s*\b(official\s+)?(full\s+)?(lyric(al)?\s+video|lyrics?\s+video|video\s+song|audio\s+song|full\s+song|music\s+video|official\s+video|official\s+audio|lyrical|lyrics)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Cleans YouTube titles into (artist, title):
     * "Daft Punk - Get Lucky (Official Video) [4K]" → (Daft Punk, Get Lucky)
     * "Narayanamma Lyric Video I Aadarsha Kutumbam I Venkatesh" (label channel) → (channel, Narayanamma)
     */
    fun splitYouTubeTitle(raw: String, channel: String): Pair<String, String> {
        val noBrackets = raw.replace(bracketNoise, "").replace(Regex("""\s+"""), " ").trim()
        // Label uploads chain context with " | " or " I " — the song is the first segment.
        var first = noBrackets.split(segmentSeparators).firstOrNull()?.trim().orEmpty().ifBlank { noBrackets }
        if (capitalISeparator.findAll(first).count() >= 2) first = first.split(capitalISeparator).first().trim()
        var cleaned = first
        repeat(2) { cleaned = cleaned.replace(trailingNoise, "").trim() }
        if (cleaned.isBlank()) cleaned = first
        val parts = cleaned.split(" - ", " – ", " — ", limit = 2)
        val artistFromChannel = channel.replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "").replace(Regex("""VEVO$"""), "").trim()
        return if (parts.size == 2 && parts[0].length in 1..60 && parts[1].isNotBlank()) parts[0].trim() to parts[1].trim()
        else artistFromChannel.ifBlank { "Unknown artist" } to cleaned.ifBlank { raw }
    }

    /** Human-readable byte sizes: 950 B, 12.4 KB, 6.1 MB, 1.20 GB. */
    fun bytes(n: Long): String = when {
        n < 1_000 -> "$n B"
        n < 1_000_000 -> "%.1f KB".format(n / 1e3)
        n < 1_000_000_000 -> "%.1f MB".format(n / 1e6)
        else -> "%.2f GB".format(n / 1e9)
    }
}
