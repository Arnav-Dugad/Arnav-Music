package com.arnav.music.core.youtube

import com.arnav.music.core.diagnostics.UsageMeter
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.quota.YouTubeCosts

/** A playlist on the signed-in user's YouTube account. */
data class RemotePlaylist(
    val id: String,
    val title: String,
    val itemCount: Int,
    val artworkUrl: String?,
    /** "Liked videos" — readable by its owner through the API, unlike YouTube Music's "Liked music". */
    val liked: Boolean = false,
)

data class ImportProgress(
    val playlist: String,
    val playlistIndex: Int,
    val playlistCount: Int,
    val tracksRead: Int,
    val tracksExpected: Int,
)

data class ImportSummary(val playlists: Int, val tracks: Int, val skipped: Int)

/**
 * Copies the user's own YouTube playlists into Arnav playlists using the official Data API with a
 * youtube.readonly OAuth token. Only metadata is read (titles, ids, artwork) — playback still goes
 * through the visible YouTube player. The token is passed per call and never stored or logged.
 *
 * Quota: playlists.list and playlistItems.list cost 1 unit per page of 50; videos.list 1 unit per 50.
 * A 200-song playlist costs about 8 units out of the 10,000 daily free quota.
 */
class YouTubeImporter(
    private val api: YouTubeApi,
    private val library: LibraryRepository,
    private val usage: UsageMeter,
) {
    suspend fun playlists(token: String): List<RemotePlaylist> {
        val out = ArrayList<RemotePlaylist>()
        var page: String? = null
        var pages = 0
        do {
            val r = api.myPlaylists(token, page)
            usage.youtubeCall(YouTubeCosts.PLAYLIST_ITEMS, false)
            r.items.forEach { p ->
                out += RemotePlaylist(p.id, p.snippet.title.ifBlank { "Untitled playlist" }, p.contentDetails.itemCount ?: 0, p.snippet.thumbnails.small())
            }
            page = r.nextPageToken
        } while (page != null && ++pages < MAX_PAGES)
        // "LL" is the owner's Liked videos list; listed first, and quietly dropped if the account hides it.
        val liked = runCatching { api.playlistItems("LL", null, token) }.getOrNull()
        if (liked != null && liked.items.isNotEmpty()) {
            usage.youtubeCall(YouTubeCosts.PLAYLIST_ITEMS, false)
            val art = liked.items.firstOrNull()?.snippet?.thumbnails?.small()
            out.add(0, RemotePlaylist("LL", "Liked videos", -1, art, liked = true))
        }
        return out
    }

    /**
     * Imports each selected playlist, reporting progress as pages arrive. Unembeddable, private and
     * deleted videos are skipped (they could never play inside the app anyway).
     */
    suspend fun import(token: String, selected: List<RemotePlaylist>, onProgress: (ImportProgress) -> Unit): ImportSummary {
        var tracksTotal = 0
        var skipped = 0
        selected.forEachIndexed { index, pl ->
            val ids = ArrayList<String>()
            var page: String? = null
            var pages = 0
            do {
                val r = api.playlistItems(pl.id, page, token)
                usage.youtubeCall(YouTubeCosts.PLAYLIST_ITEMS, false)
                r.items.mapNotNullTo(ids) { it.contentDetails.videoId }
                onProgress(ImportProgress(pl.title, index, selected.size, ids.size, pl.itemCount.coerceAtLeast(ids.size)))
                page = r.nextPageToken
            } while (page != null && ++pages < MAX_PAGES && ids.size < MAX_TRACKS)
            val wanted = ids.distinct().take(MAX_TRACKS)
            val tracks = ArrayList<Track>(wanted.size)
            wanted.chunked(50).forEach { chunk ->
                val r = api.videos(chunk, token)
                usage.youtubeCall(YouTubeCosts.VIDEOS_LIST, false)
                val byId = r.items.filter { it.status.embeddable && it.snippet.liveBroadcastContent != "live" }.associateBy { it.id }
                chunk.forEach { id -> byId[id]?.let { tracks += it.toTrack() } ?: skipped++ }
            }
            library.importPlaylist(
                remoteId = pl.id,
                name = pl.title,
                description = "Imported from YouTube",
                tracks = tracks,
            )
            tracksTotal += tracks.size
        }
        return ImportSummary(selected.size, tracksTotal, skipped)
    }

    private companion object {
        const val MAX_PAGES = 20
        /** Matches the playlist sync limit. */
        const val MAX_TRACKS = 500
    }
}
