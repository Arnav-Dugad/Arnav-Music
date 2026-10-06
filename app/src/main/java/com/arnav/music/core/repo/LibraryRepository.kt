package com.arnav.music.core.repo

import com.arnav.music.core.common.Clock
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.db.LikeEntity
import com.arnav.music.core.db.PlayEventEntity
import com.arnav.music.core.db.PlaylistEntity
import com.arnav.music.core.db.PlaylistTrackEntity
import com.arnav.music.core.db.TrackEntity
import com.arnav.music.core.firebase.CloudSync
import com.arnav.music.core.local.LocalMediaSource
import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.Playlist
import com.arnav.music.domain.model.PlaylistKind
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

/**
 * Unified library across sources: likes, Arnav playlists, history and on-device music.
 * All writes are local-first and optimistic; cloud sync follows asynchronously.
 */
class LibraryRepository(
    private val db: ArnavDatabase,
    private val local: LocalMediaSource,
    private val sync: CloudSync,
    private val clock: Clock,
    scope: CoroutineScope,
) {
    val likedIds: StateFlow<Set<TrackId>> = db.likes().likedIds()
        .map { ids -> ids.map(::TrackId).toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val likedTracks: Flow<List<Track>> = db.likes().likedTracks().map { list -> list.map { it.toDomain() } }

    val playlists: Flow<List<Playlist>> = db.playlists().observe().map { list ->
        list.map {
            Playlist(it.id, it.name, it.description, runCatching { PlaylistKind.valueOf(it.kind) }.getOrDefault(PlaylistKind.ARNAV), it.artworkUrl, it.trackCount, it.updatedAt, it.pinned)
        }
    }

    val localTracks: StateFlow<List<Track>> = local.tracks().stateIn(scope, SharingStarted.WhileSubscribed(10_000), emptyList())

    val eventCount: Flow<Int> = db.events().count()

    fun recentlyPlayed(limit: Int = 30): Flow<List<Pair<TrackId, Long>>> =
        db.events().recentTracks(limit).map { rows -> rows.map { TrackId(it.trackId) to it.lastPlayed } }

    suspend fun remember(tracks: List<Track>) {
        val now = clock.now()
        db.tracks().upsert(tracks.map { TrackEntity.from(it, now) })
    }

    suspend fun tracks(ids: List<TrackId>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val found = ids.chunked(500).flatMap { chunk -> db.tracks().getAll(chunk.map { it.value }) }.associateBy { it.id }
        val missingLocal = ids.filter { it.value !in found && it.source == com.arnav.music.domain.model.SourceType.LOCAL }
        val localResolved = missingLocal.mapNotNull { local.track(it) }.associateBy { it.id.value }
        return ids.mapNotNull { id -> found[id.value]?.toDomain() ?: localResolved[id.value] }
    }

    suspend fun allKnownTracks(): List<Track> = db.tracks().all().map { it.toDomain() } + localTracks.value

    suspend fun toggleLike(track: Track): Boolean {
        val now = clock.now()
        remember(listOf(track))
        val existing = db.likes().get(track.id.value)
        val nowLiked = existing == null || existing.deleted
        db.likes().upsert(LikeEntity(track.id.value, if (nowLiked) now else existing!!.likedAt, now, deleted = !nowLiked, dirty = true))
        sync.requestSync()
        return nowLiked
    }

    suspend fun createPlaylist(name: String, description: String = "", tracks: List<Track> = emptyList()): String {
        val id = "arn_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val now = clock.now()
        db.playlists().upsert(PlaylistEntity(id, name.trim().take(100).ifBlank { "New playlist" }, description.take(500), PlaylistKind.ARNAV.name, tracks.firstOrNull()?.artworkUrl, false, now, now))
        if (tracks.isNotEmpty()) {
            remember(tracks)
            db.playlists().replaceTracks(id, tracks.map { it.id.value }, now)
        }
        sync.requestSync()
        return id
    }

    suspend fun addToPlaylist(playlistId: String, tracks: List<Track>) {
        val p = db.playlists().get(playlistId) ?: return
        remember(tracks)
        val now = clock.now()
        var pos = db.playlists().maxPosition(playlistId)
        tracks.forEach { db.playlists().addTrack(PlaylistTrackEntity(playlistId, it.id.value, ++pos, now)) }
        db.playlists().upsert(p.copy(updatedAt = now, dirty = true, artworkUrl = p.artworkUrl ?: tracks.firstOrNull()?.artworkUrl))
        sync.requestSync()
    }

    suspend fun removeFromPlaylist(playlistId: String, trackId: TrackId) {
        val p = db.playlists().get(playlistId) ?: return
        db.playlists().removeTrack(playlistId, trackId.value)
        db.playlists().upsert(p.copy(updatedAt = clock.now(), dirty = true))
        sync.requestSync()
    }

    suspend fun reorderPlaylist(playlistId: String, ids: List<TrackId>) {
        val p = db.playlists().get(playlistId) ?: return
        val now = clock.now()
        db.playlists().replaceTracks(playlistId, ids.map { it.value }, now)
        db.playlists().upsert(p.copy(updatedAt = now, dirty = true))
        sync.requestSync()
    }

    suspend fun renamePlaylist(playlistId: String, name: String, description: String) {
        val p = db.playlists().get(playlistId) ?: return
        db.playlists().upsert(p.copy(name = name.take(100), description = description.take(500), updatedAt = clock.now(), dirty = true))
        sync.requestSync()
    }

    suspend fun togglePin(playlistId: String) {
        val p = db.playlists().get(playlistId) ?: return
        db.playlists().upsert(p.copy(pinned = !p.pinned, updatedAt = clock.now(), dirty = true))
        sync.requestSync()
    }

    suspend fun deletePlaylist(playlistId: String) {
        val p = db.playlists().get(playlistId) ?: return
        db.playlists().clear(playlistId)
        db.playlists().upsert(p.copy(deleted = true, updatedAt = clock.now(), dirty = true))
        sync.requestSync()
    }

    /** Saves a YouTube playlist reference into the library (read-only, clearly labelled). */
    suspend fun saveYouTubePlaylist(playlist: Playlist) {
        val now = clock.now()
        db.playlists().upsert(PlaylistEntity(playlist.id, playlist.name, playlist.description, PlaylistKind.YOUTUBE.name, playlist.artworkUrl, false, now, now, dirty = false, remoteRef = playlist.id.removePrefix("ytpl:")))
    }

    /**
     * Creates or refreshes an Arnav playlist copied from the user's YouTube account. The id is stable
     * per source playlist, so importing again updates it instead of duplicating it. Synced like any
     * Arnav playlist (capped at 500 tracks, the sync schema limit).
     */
    suspend fun importPlaylist(remoteId: String, name: String, description: String, tracks: List<Track>): String {
        val id = "ytimp_" + remoteId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(56)
        val now = clock.now()
        val existing = db.playlists().get(id)
        val capped = tracks.distinctBy { it.id }.take(500)
        remember(capped)
        db.playlists().upsert(
            PlaylistEntity(
                id, name.trim().take(100).ifBlank { "YouTube playlist" }, description.take(500), PlaylistKind.ARNAV.name,
                capped.firstOrNull()?.artworkUrl ?: existing?.artworkUrl, existing?.pinned ?: false,
                existing?.createdAt ?: now, now, deleted = false, dirty = true, remoteRef = remoteId,
            ),
        )
        db.playlists().replaceTracks(id, capped.map { it.id.value }, now)
        sync.requestSync()
        return id
    }

    fun playlist(id: String): Flow<PlaylistEntity?> = db.playlists().observeOne(id)
    fun playlistTracks(id: String): Flow<List<Track>> = db.playlists().tracks(id).map { l -> l.map { it.toDomain() } }

    suspend fun recordPlay(event: PlayEvent, source: String) {
        db.events().insert(
            PlayEventEntity(
                trackId = event.trackId.value, artistKey = event.artistKey, startedAt = event.startedAt,
                listenedMs = event.listenedMs, durationMs = event.trackDurationMs, completed = event.completed,
                skipped = event.skipped, source = source,
            ),
        )
    }

    suspend fun events(sinceMs: Long = 0L): List<PlayEvent> = db.events().since(sinceMs).map { it.toDomain() }
    suspend fun eventsBetween(from: Long, to: Long): List<PlayEvent> = db.events().between(from, to).map { it.toDomain() }
    fun observeEvents(sinceMs: Long): Flow<List<PlayEvent>> = db.events().observeSince(sinceMs).map { l -> l.map { it.toDomain() } }
    suspend fun firstEventAt(): Long? = db.events().firstEventAt()
    suspend fun clearHistory() = db.events().clear()
    suspend fun tracksByArtist(artistKey: String): List<Track> = db.tracks().byArtist(artistKey).map { it.toDomain() }
    suspend fun searchKnown(q: String): List<Track> = db.tracks().searchLocal(q).map { it.toDomain() }

    private fun PlayEventEntity.toDomain() = PlayEvent(TrackId(trackId), artistKey, startedAt, listenedMs, durationMs, completed, skipped)
}
