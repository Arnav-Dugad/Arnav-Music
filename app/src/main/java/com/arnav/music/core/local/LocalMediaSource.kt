package com.arnav.music.core.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.arnav.music.domain.model.PlaybackCapabilities
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import com.arnav.music.domain.provider.LibraryProvider
import com.arnav.music.domain.provider.MusicCatalogProvider
import com.arnav.music.domain.provider.PlaybackProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** User-owned audio from MediaStore — the fully controllable, background-capable source. */
class LocalMediaSource(private val context: Context) : LibraryProvider, MusicCatalogProvider, PlaybackProvider {
    override val source = SourceType.LOCAL

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun capabilities(track: Track) = PlaybackCapabilities.LocalMedia

    /** Re-queries whenever MediaStore changes; emits empty when permission is missing. */
    override fun tracks(): Flow<List<Track>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        trySend(Unit)
        runCatching { context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer) }
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.conflate().map { query() }.flowOn(Dispatchers.IO)

    override suspend fun track(id: TrackId): Track? = withContext(Dispatchers.IO) {
        id.nativeId.toLongOrNull()?.let { query(selectionId = it).firstOrNull() }
    }

    private fun query(selectionId: Long? = null): List<Track> {
        if (!hasPermission()) return emptyList()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.YEAR,
        )
        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 20000")
            if (selectionId != null) append(" AND ${MediaStore.Audio.Media._ID} = $selectionId")
        }
        val out = ArrayList<Track>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val albumId = c.getLong(albumIdCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val artist = c.getString(artistCol)?.takeUnless { it.isBlank() || it == "<unknown>" } ?: "Unknown artist"
                    out += Track(
                        id = TrackId.local(id),
                        title = c.getString(titleCol)?.ifBlank { null } ?: "Untitled",
                        artist = artist,
                        album = c.getString(albumCol)?.takeUnless { it.isBlank() || it == "<unknown>" },
                        durationMs = c.getLong(durCol).takeIf { it > 0 },
                        artworkUrl = ContentUris.withAppendedId(ALBUM_ART, albumId).toString(),
                        playbackRef = uri.toString(),
                        year = c.getInt(yearCol).takeIf { it in 1900..2100 },
                    )
                }
            }
        }
        return out
    }

    companion object {
        private val ALBUM_ART = android.net.Uri.parse("content://media/external/audio/albumart")
    }
}
