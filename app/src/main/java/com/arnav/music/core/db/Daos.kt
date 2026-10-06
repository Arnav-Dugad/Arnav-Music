package com.arnav.music.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Upsert suspend fun upsert(tracks: List<TrackEntity>)
    @Query("SELECT * FROM tracks WHERE id = :id") suspend fun get(id: String): TrackEntity?
    @Query("SELECT * FROM tracks WHERE id IN (:ids)") suspend fun getAll(ids: List<String>): List<TrackEntity>
    @Query("SELECT * FROM tracks") suspend fun all(): List<TrackEntity>
    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :q || '%' OR artist LIKE '%' || :q || '%' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun searchLocal(q: String, limit: Int = 30): List<TrackEntity>
    @Query("SELECT * FROM tracks WHERE artistKey = :artistKey ORDER BY updatedAt DESC")
    suspend fun byArtist(artistKey: String): List<TrackEntity>
    @Query("SELECT COUNT(*) FROM tracks") suspend fun count(): Int
}

@Dao
interface LikeDao {
    @Query("SELECT l.trackId FROM likes l WHERE l.deleted = 0 ORDER BY l.likedAt DESC")
    fun likedIds(): Flow<List<String>>

    @Query("SELECT t.* FROM tracks t INNER JOIN likes l ON l.trackId = t.id WHERE l.deleted = 0 ORDER BY l.likedAt DESC")
    fun likedTracks(): Flow<List<TrackEntity>>

    @Upsert suspend fun upsert(like: LikeEntity)
    @Upsert suspend fun upsertAll(likes: List<LikeEntity>)
    @Query("SELECT * FROM likes WHERE trackId = :id") suspend fun get(id: String): LikeEntity?
    @Query("SELECT * FROM likes WHERE dirty = 1") suspend fun dirty(): List<LikeEntity>
    @Query("SELECT * FROM likes") suspend fun all(): List<LikeEntity>
    @Query("UPDATE likes SET dirty = 0 WHERE trackId IN (:ids)") suspend fun markClean(ids: List<String>)
}

data class PlaylistWithCount(
    val id: String,
    val name: String,
    val description: String,
    val kind: String,
    val artworkUrl: String?,
    val pinned: Boolean,
    val updatedAt: Long,
    val trackCount: Int,
)

@Dao
interface PlaylistDao {
    @Query(
        """SELECT p.id, p.name, p.description, p.kind, p.artworkUrl, p.pinned, p.updatedAt,
           (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.id) AS trackCount
           FROM playlists p WHERE p.deleted = 0 ORDER BY p.pinned DESC, p.updatedAt DESC"""
    )
    fun observe(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists WHERE id = :id") suspend fun get(id: String): PlaylistEntity?
    @Query("SELECT * FROM playlists WHERE id = :id") fun observeOne(id: String): Flow<PlaylistEntity?>
    @Upsert suspend fun upsert(p: PlaylistEntity)
    @Upsert suspend fun upsertAll(p: List<PlaylistEntity>)
    @Query("SELECT * FROM playlists WHERE dirty = 1") suspend fun dirty(): List<PlaylistEntity>
    @Query("SELECT * FROM playlists") suspend fun all(): List<PlaylistEntity>
    @Query("UPDATE playlists SET dirty = 0 WHERE id IN (:ids)") suspend fun markClean(ids: List<String>)

    @Query("SELECT t.* FROM tracks t INNER JOIN playlist_tracks pt ON pt.trackId = t.id WHERE pt.playlistId = :id ORDER BY pt.position ASC")
    fun tracks(id: String): Flow<List<TrackEntity>>

    @Query("SELECT trackId FROM playlist_tracks WHERE playlistId = :id ORDER BY position ASC")
    suspend fun trackIds(id: String): List<String>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :id")
    suspend fun maxPosition(id: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addTrack(e: PlaylistTrackEntity): Long
    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrack(playlistId: String, trackId: String)
    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId") suspend fun clear(playlistId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTracks(e: List<PlaylistTrackEntity>)

    @Transaction
    suspend fun replaceTracks(playlistId: String, ids: List<String>, now: Long) {
        clear(playlistId)
        insertTracks(ids.distinct().mapIndexed { i, id -> PlaylistTrackEntity(playlistId, id, i, now) })
    }
}

data class TrackPlayCount(val trackId: String, val plays: Int, val lastPlayed: Long)

@Dao
interface PlayEventDao {
    @Insert suspend fun insert(e: PlayEventEntity)
    @Query("SELECT * FROM play_events WHERE startedAt >= :since ORDER BY startedAt ASC")
    suspend fun since(since: Long): List<PlayEventEntity>
    @Query("SELECT * FROM play_events WHERE startedAt >= :since ORDER BY startedAt ASC")
    fun observeSince(since: Long): Flow<List<PlayEventEntity>>
    @Query("SELECT * FROM play_events WHERE startedAt BETWEEN :from AND :to ORDER BY startedAt ASC")
    suspend fun between(from: Long, to: Long): List<PlayEventEntity>
    @Query("SELECT trackId, COUNT(*) AS plays, MAX(startedAt) AS lastPlayed FROM play_events GROUP BY trackId ORDER BY lastPlayed DESC LIMIT :limit")
    fun recentTracks(limit: Int): Flow<List<TrackPlayCount>>
    @Query("SELECT COUNT(*) FROM play_events") fun count(): Flow<Int>
    @Query("SELECT MIN(startedAt) FROM play_events") suspend fun firstEventAt(): Long?
    @Query("DELETE FROM play_events") suspend fun clear()
}

@Dao
interface SearchDao {
    @Query("SELECT * FROM search_cache WHERE `key` = :key") suspend fun get(key: String): SearchCacheEntity?
    @Upsert suspend fun put(e: SearchCacheEntity)
    @Query("DELETE FROM search_cache WHERE fetchedAt < :before") suspend fun prune(before: Long)
    @Query("SELECT COUNT(*) FROM search_cache") suspend fun count(): Int
    @Query("SELECT * FROM search_cache ORDER BY fetchedAt DESC LIMIT 40") suspend fun recentPayloads(): List<SearchCacheEntity>

    @Query("SELECT * FROM recent_searches ORDER BY searchedAt DESC LIMIT :limit") fun recent(limit: Int = 12): Flow<List<RecentSearchEntity>>
    @Upsert suspend fun addRecent(e: RecentSearchEntity)
    @Query("DELETE FROM recent_searches WHERE normalized = :key") suspend fun removeRecent(key: String)
    @Query("DELETE FROM recent_searches") suspend fun clearRecent()
    @Query("DELETE FROM search_cache") suspend fun clearCache()
}

@Dao
interface AiCacheDao {
    @Query("SELECT * FROM ai_cache WHERE `key` = :key AND promptVersion = :version") suspend fun get(key: String, version: String): AiCacheEntity?
    @Upsert suspend fun put(e: AiCacheEntity)
    @Query("DELETE FROM ai_cache") suspend fun clear()
    @Query("SELECT COUNT(*) FROM ai_cache") suspend fun count(): Int
}

@Dao
interface KvSyncDao {
    @Query("SELECT * FROM kv_sync WHERE `key` = :key") suspend fun get(key: String): KvSyncEntity?
    @Upsert suspend fun put(e: KvSyncEntity)
    @Query("SELECT * FROM kv_sync WHERE dirty = 1") suspend fun dirty(): List<KvSyncEntity>
}
