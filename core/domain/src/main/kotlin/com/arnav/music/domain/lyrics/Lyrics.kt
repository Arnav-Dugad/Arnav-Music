package com.arnav.music.domain.lyrics

/** One sung word (or syllable) with absolute timing in milliseconds. */
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

/**
 * One displayed lyric line. An empty [text] marks an instrumental break.
 * [words] is non-empty only for word-synced ("enhanced") lyrics.
 */
data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
) {
    val isInstrumental: Boolean get() = text.isBlank()
}

sealed interface Lyrics {
    /** Time-synced lyrics, sorted by [LyricLine.startMs]. */
    data class Synced(val lines: List<LyricLine>) : Lyrics

    /** Lyrics without timing. Blank entries separate stanzas. */
    data class Plain(val lines: List<String>) : Lyrics
}

/** Readable text lines regardless of timing (instrumental breaks become stanza gaps). */
fun Lyrics.displayLines(): List<String> = when (this) {
    is Lyrics.Plain -> lines
    is Lyrics.Synced -> {
        val out = ArrayList<String>(lines.size)
        for (l in lines) {
            if (l.isInstrumental) {
                if (out.isNotEmpty() && out.last().isNotEmpty()) out.add("")
            } else out.add(l.text)
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
        out
    }
}

object LyricsTiming {
    /** Index of the line being sung at [positionMs]; -1 before the first line (or when empty). */
    fun activeIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty() || positionMs < lines[0].startMs) return -1
        var lo = 0
        var hi = lines.lastIndex
        // Largest index whose start <= position.
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lines[mid].startMs <= positionMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** 0..1 progress through [line]. */
    fun lineProgress(line: LyricLine, positionMs: Long): Float = progress(line.startMs, line.endMs, positionMs)

    /** 0..1 progress through [word]. */
    fun wordProgress(word: LyricWord, positionMs: Long): Float = progress(word.startMs, word.endMs, positionMs)

    private fun progress(start: Long, end: Long, pos: Long): Float {
        if (pos <= start) return if (end <= start && pos >= start) 1f else 0f
        if (end <= start || pos >= end) return 1f
        return ((pos - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
    }
}
