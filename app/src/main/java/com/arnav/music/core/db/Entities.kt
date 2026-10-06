package com.arnav.music.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId

@Entity(tableName = "tracks", indices = [Index("artistKey")])
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val artistKey: String,
    val album: String?,
    val durationMs: Long?,
    val artworkUrl: String?,
    val playbackRef: String,
    val channelId: String?,
    val genres: String,
    val energy: Float?,
    val year: Int?,
    val updatedAt: Long,
    val variant: String? = null,
    val credits: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val compilation: Boolean = false,
) {
    fun toDomain() = Track(
        id = TrackId(id), title = title, artist = artist, album = album, durationMs = durationMs,
        artworkUrl = artworkUrl, playbackRef = playbackRef, channelId = channelId,
        genres = if (genres.isBlank()) emptyList() else genres.split('|'), energy = energy, year = year,
        variant = variant?.let { v -> com.arnav.music.domain.model.MediaVariant.entries.firstOrNull { it.name == v } },
        credits = credits,
        compilation = compilation,
    )

    companion object {
        fun from(t: Track, now: Long) = TrackEntity(
            t.id.value, t.title, t.artist, t.artistKey, t.album, t.durationMs, t.artworkUrl, t.playbackRef,
            t.channelId, t.genres.joinToString("|"), t.energy, t.year, now, t.variant?.name, t.credits, t.compilation,
        )
    }
}

@Entity(tableName = "likes")
data class LikeEntity(
    @PrimaryKey val trackId: String,
    val likedAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val kind: String,
    val artworkUrl: String?,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
    /** Source-side id (e.g. a YouTube playlist id) for imported/linked collections. */
    val remoteRef: String? = null,
)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "trackId"], indices = [Index("trackId")])
data class PlaylistTrackEntity(
    val playlistId: String,
    val trackId: String,
    val position: Int,
    val addedAt: Long,
)

@Entity(tableName = "play_events", indices = [Index("startedAt"), Index("trackId"), Index("artistKey")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val artistKey: String,
    val startedAt: Long,
    val listenedMs: Long,
    val durationMs: Long?,
    val completed: Boolean,
    val skipped: Boolean,
    val source: String,
)

@Entity(tableName = "search_cache")
data class SearchCacheEntity(
    @PrimaryKey val key: String,
    val query: String,
    val payload: String,
    val fetchedAt: Long,
    val nextPageToken: String?,
)

@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey val normalized: String,
    val display: String,
    val searchedAt: Long,
)

@Entity(tableName = "ai_cache")
data class AiCacheEntity(
    @PrimaryKey val key: String,
    val promptVersion: String,
    val response: String,
    val createdAt: Long,
)

/** Small JSON documents synced as a unit (settings, taste preferences). */
@Entity(tableName = "kv_sync")
data class KvSyncEntity(
    @PrimaryKey val key: String,
    val json: String,
    val updatedAt: Long,
    val dirty: Boolean,
)
