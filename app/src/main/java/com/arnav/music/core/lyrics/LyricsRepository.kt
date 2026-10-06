package com.arnav.music.core.lyrics

import android.content.Context
import android.net.Uri
import com.arnav.music.core.db.LyricsDao
import com.arnav.music.core.db.LyricsEntity
import com.arnav.music.domain.lyrics.EmbeddedLyrics
import com.arnav.music.domain.lyrics.LrcParser
import com.arnav.music.domain.lyrics.Lyrics
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

sealed interface LyricsState {
    data object Loading : LyricsState

    /** [source] is "embedded", "file" or "pasted"; [raw] is the stored LRC/plain text (for editing). */
    data class Ready(val lyrics: Lyrics, val source: String, val raw: String = "") : LyricsState

    data object None : LyricsState
}

/**
 * Lyrics that live on this device only. Sources, all legitimate:
 * 1. lyrics embedded in the user's own local audio file (ID3 USLT/SYLT, FLAC Vorbis comment, MP4 ©lyr),
 * 2. an .lrc/.txt file the user picks with the system file picker,
 * 3. text the user pastes.
 * Nothing is ever fetched from the network.
 */
class LyricsRepository(private val context: Context, private val dao: LyricsDao) {

    /** Tracks whose file was already scanned this process, so a file without lyrics is read once. */
    private val scanned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun observe(track: Track): Flow<LyricsState> = flow {
        emit(LyricsState.Loading)
        val id = track.id.value
        val saved = try { dao.get(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (saved == null && track.source == SourceType.LOCAL && scanned.add(id)) {
            scanEmbedded(track)
        }
        emitAll(dao.observe(id).map { toState(it, track) })
    }
        .catch { emit(LyricsState.None) }
        .distinctUntilChanged()

    /** Reads lyrics from the song file again (e.g. after the user removed them by mistake). */
    suspend fun rescanEmbedded(track: Track): Boolean {
        if (track.source != SourceType.LOCAL) return false
        scanned.add(track.id.value)
        return scanEmbedded(track, force = true)
    }

    /** Imports an .lrc/.txt document the user picked. False when unreadable or not lyrics. */
    suspend fun importFile(track: Track, uri: Uri): Boolean {
        val text = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { readText(it, MAX_IMPORT_BYTES) }
            } catch (e: Exception) {
                null
            }
        } ?: return false
        return save(track, text, SOURCE_FILE)
    }

    /** Saves pasted LRC or plain text. False when there is nothing usable. */
    suspend fun savePasted(track: Track, text: String): Boolean = save(track, text, SOURCE_PASTED)

    /**
     * Removes saved lyrics. For local files a blank marker row is kept so lyrics embedded in the
     * file don't silently come back; [rescanEmbedded] restores them.
     */
    suspend fun remove(track: Track) {
        val id = track.id.value
        try {
            if (track.source == SourceType.LOCAL) {
                dao.upsert(LyricsEntity(id, "", synced = false, source = SOURCE_REMOVED, updatedAt = System.currentTimeMillis()))
            } else {
                dao.delete(id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing sensible to surface; the row simply stays.
        }
    }

    private suspend fun save(track: Track, raw: String, source: String): Boolean {
        val text = normalize(raw).take(MAX_TEXT_CHARS)
        val parsed = LrcParser.parse(text, track.durationMs) ?: return false
        return try {
            dao.upsert(LyricsEntity(track.id.value, text, synced = parsed is Lyrics.Synced, source = source, updatedAt = System.currentTimeMillis()))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun scanEmbedded(track: Track, force: Boolean = false): Boolean {
        val text = withContext(Dispatchers.IO) { readEmbedded(track.playbackRef) } ?: return false
        val parsed = LrcParser.parse(text, track.durationMs) ?: return false
        return try {
            val id = track.id.value
            // A manual import/paste that landed while we were reading wins.
            val existing = dao.get(id)
            if (!force && existing != null) return false
            if (force && existing != null && existing.source != SOURCE_REMOVED && existing.text.isNotBlank()) return false
            dao.upsert(LyricsEntity(id, text, synced = parsed is Lyrics.Synced, source = SOURCE_EMBEDDED, updatedAt = System.currentTimeMillis()))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun toState(entity: LyricsEntity?, track: Track): LyricsState {
        if (entity == null || entity.text.isBlank()) return LyricsState.None
        val parsed = LrcParser.parse(entity.text, track.durationMs) ?: return LyricsState.None
        return LyricsState.Ready(parsed, entity.source, entity.text)
    }

    /** Blocking; call on IO. Returns lyrics text embedded in the local file, or null. */
    private fun readEmbedded(ref: String): String? {
        val uri = try { Uri.parse(ref) } catch (e: Exception) { return null }
        if (uri.scheme != "content" && uri.scheme != "file") return null
        // Random access when possible: lets us skip artwork and reach an MP4 `moov` at the end.
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val channel = input.channel
                    val size = channel.size()
                    if (size > 0) return EmbeddedLyrics.fromSource(ChannelSource(channel, size))
                }
            }
        } catch (e: Exception) {
            // Fall through to a plain stream (non-seekable providers).
        } catch (e: OutOfMemoryError) {
            return null
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                EmbeddedLyrics.fromBytes(readBytes(input, HEAD_BYTES))
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Positional reads over a file channel. Reads are capped by [EmbeddedLyrics] itself. */
    private class ChannelSource(private val channel: FileChannel, override val length: Long) : EmbeddedLyrics.Source {
        override fun read(position: Long, size: Int): ByteArray {
            if (position < 0 || size <= 0 || position >= length) return ByteArray(0)
            val n = minOf(size.toLong(), length - position, MAX_READ.toLong()).toInt()
            val buffer = ByteBuffer.allocate(n)
            var at = position
            while (buffer.hasRemaining()) {
                val r = channel.read(buffer, at)
                if (r <= 0) break
                at += r
            }
            val got = buffer.position()
            return if (got == n) buffer.array() else buffer.array().copyOf(got)
        }
    }

    companion object {
        const val SOURCE_EMBEDDED = "embedded"
        const val SOURCE_FILE = "file"
        const val SOURCE_PASTED = "pasted"
        /** Marker row: the user removed lyrics for a local file. Rendered as no lyrics. */
        const val SOURCE_REMOVED = "removed"

        private const val HEAD_BYTES = 2 * 1024 * 1024
        private const val MAX_READ = 16 * 1024 * 1024
        private const val MAX_IMPORT_BYTES = 512 * 1024
        private const val MAX_TEXT_CHARS = 200_000

        private fun readBytes(input: InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (total < limit) {
                val r = input.read(buf, 0, minOf(buf.size, limit - total))
                if (r < 0) break
                out.write(buf, 0, r)
                total += r
            }
            return out.toByteArray()
        }

        /** Text up to [limit] bytes: UTF-8 (BOM stripped), or UTF-16 when it carries a BOM. */
        private fun readText(input: InputStream, limit: Int): String {
            val b = readBytes(input, limit)
            if (b.size >= 2) {
                val b0 = b[0].toInt() and 0xFF
                val b1 = b[1].toInt() and 0xFF
                if (b0 == 0xFF && b1 == 0xFE) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
                if (b0 == 0xFE && b1 == 0xFF) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
            }
            if (b.size >= 3 && (b[0].toInt() and 0xFF) == 0xEF && (b[1].toInt() and 0xFF) == 0xBB && (b[2].toInt() and 0xFF) == 0xBF) {
                return String(b, 3, b.size - 3, Charsets.UTF_8)
            }
            return String(b, Charsets.UTF_8)
        }

        private fun normalize(raw: String): String =
            raw.removePrefix("﻿").replace("\u0000", "").replace("\r\n", "\n").replace('\r', '\n').trim()
    }
}
