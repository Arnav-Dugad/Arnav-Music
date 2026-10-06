package com.arnav.music.domain.search

import java.text.Normalizer

/**
 * Normalises search input so that "  Daft PUNK!! " and "daft punk" share one cache entry —
 * every cache hit is a YouTube search (100 quota units) not spent.
 */
object QueryNormalizer {
    private val diacritics = Regex("""\p{Mn}+""")
    private val punctuation = Regex("""[^\p{L}\p{N}\s&']""")
    private val spaces = Regex("""\s+""")
    private val noise = setOf("official", "video", "audio", "lyrics", "lyric", "hd", "4k", "mv")

    const val MIN_REMOTE_LENGTH = 2
    const val DEBOUNCE_MS = 650L

    fun normalize(raw: String): String {
        val decomposed = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
        return decomposed.replace(diacritics, "")
            .replace(punctuation, " ")
            .replace(spaces, " ")
            .trim()
    }

    /** Cache key: normalised, with noise words dropped and tokens kept in order. */
    fun cacheKey(raw: String, filter: String = "all"): String {
        val tokens = normalize(raw).split(' ').filter { it.isNotEmpty() && it !in noise }
        return filter + "|" + tokens.joinToString(" ")
    }

    fun isRemoteWorthy(raw: String): Boolean {
        val n = normalize(raw)
        return n.length >= MIN_REMOTE_LENGTH && n.any { it.isLetterOrDigit() }
    }

    /** Local fuzzy match score 0..1 used for instant suggestions from cache/library. */
    fun matchScore(query: String, candidate: String): Float {
        val q = normalize(query)
        val c = normalize(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0f
        if (c == q) return 1f
        if (c.startsWith(q)) return 0.9f
        val words = c.split(' ')
        if (words.any { it.startsWith(q) }) return 0.75f
        if (c.contains(q)) return 0.6f
        val qt = q.split(' ').filter { it.isNotEmpty() }
        val hits = qt.count { t -> words.any { it.startsWith(t) } }
        return if (qt.isEmpty()) 0f else 0.5f * hits / qt.size
    }
}
