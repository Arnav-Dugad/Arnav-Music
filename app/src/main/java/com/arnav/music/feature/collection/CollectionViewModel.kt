package com.arnav.music.feature.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.common.Clock
import com.arnav.music.core.db.PendingMatchEntity
import com.arnav.music.core.importer.ImportMatcher
import com.arnav.music.core.importer.MatchNowResult
import com.arnav.music.core.repo.IntelligenceRepository
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.core.youtube.YouTubeImporter
import com.arnav.music.core.youtube.YouTubeRepository
import com.arnav.music.domain.intelligence.SmartPlaylist
import com.arnav.music.domain.model.ArtistKey
import com.arnav.music.domain.model.Playlist
import com.arnav.music.domain.model.PlaylistKind
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.provider.MusicError
import com.arnav.music.domain.provider.SearchFilter
import com.arnav.music.feature.library.describeYouTubeError
import com.arnav.music.ui.CollectionKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SmartSort(val label: String) { DEFAULT("Default"), ENERGY_UP("Energy rising"), VARIETY("Artist variety"), TITLE("A–Z") }

data class CollectionUi(
    val loading: Boolean = true,
    val title: String = "",
    val subtitle: String = "",
    val kindLabel: String = "",
    val description: String = "",
    val tracks: List<Track> = emptyList(),
    val editable: Boolean = false,
    val pinned: Boolean = false,
    val savedToLibrary: Boolean = false,
    val youtube: Boolean = false,
    val error: MusicError? = null,
    val historyDays: List<Pair<String, List<Track>>> = emptyList(),
    /** Songs from a Spotify/CSV import not matched yet, in source order (shown dimmed after the tracks). */
    val pending: List<PendingMatchEntity> = emptyList(),
    /** Copied from the user's YouTube account; can be refreshed from YouTube. */
    val youtubeImport: Boolean = false,
)

class CollectionViewModel(
    private val library: LibraryRepository,
    private val intelligence: IntelligenceRepository,
    private val youtube: YouTubeRepository,
    private val clock: Clock,
    private val matcher: ImportMatcher,
    private val youtubeImporter: YouTubeImporter,
) : ViewModel() {
    private val _ui = MutableStateFlow(CollectionUi())
    val ui: StateFlow<CollectionUi> = _ui.asStateFlow()
    val filter = MutableStateFlow("")
    val sort = MutableStateFlow(SmartSort.DEFAULT)
    private var job: Job? = null
    private var kind: CollectionKind = CollectionKind.LIKED
    private var id: String = ""
    private var remoteRef: String? = null

    /** Pending rows being matched right now (inline spinner). */
    private val _matching = MutableStateFlow<Set<Long>>(emptySet())
    val matching: StateFlow<Set<Long>> = _matching.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun load(kind: CollectionKind, id: String) {
        if (job != null && this.kind == kind && this.id == id) return
        this.kind = kind; this.id = id
        job?.cancel()
        job = viewModelScope.launch {
            when (kind) {
                CollectionKind.LIKED -> library.likedTracks.collect { set(CollectionUi(false, "Liked songs", "", "Your favourites", tracks = it)) }
                CollectionKind.LOCAL -> library.localTracks.collect { set(CollectionUi(false, "On this device", "", "Background playback · offline", tracks = it)) }
                CollectionKind.PLAYLIST -> combine(library.playlist(id), library.playlistTracks(id), matcher.pendingFor(id)) { p, t, pending -> Triple(p, t, pending) }.collect { (p, t, pending) ->
                    if (p == null || p.deleted) set(CollectionUi(false, "Playlist not found", error = MusicError.Unavailable))
                    else {
                        val imported = p.remoteRef != null && p.id.startsWith("ytimp_")
                        remoteRef = if (imported) p.remoteRef else null
                        set(
                            CollectionUi(
                                false, p.name, "", if (p.kind == PlaylistKind.YOUTUBE.name) "YouTube playlist" else "Arnav playlist", p.description, t,
                                editable = p.kind == PlaylistKind.ARNAV.name, pinned = p.pinned, pending = pending, youtubeImport = imported,
                            ),
                        )
                    }
                }
                CollectionKind.YOUTUBE_PLAYLIST -> {
                    val saved = library.playlist(id)
                    _ui.value = CollectionUi(true, "YouTube playlist", kindLabel = "YouTube playlist", youtube = true)
                    youtube.playlistTracks(id.removePrefix("ytpl:"))
                        .onSuccess { tracks ->
                            library.remember(tracks)
                            saved.collect { p -> set(CollectionUi(false, p?.name ?: titleFromCache(tracks), "", "YouTube playlist", p?.description.orEmpty(), tracks, savedToLibrary = p != null && !p.deleted, youtube = true)) }
                        }
                        .onFailure { e -> set(CollectionUi(false, "YouTube playlist", kindLabel = "YouTube playlist", youtube = true, error = e as? MusicError ?: MusicError.Unknown(""))) }
                }
                CollectionKind.SMART -> {
                    val sp = runCatching { SmartPlaylist.valueOf(id) }.getOrDefault(SmartPlaylist.HEAVY_ROTATION)
                    val mix = intelligence.smartMix(sp)
                    set(CollectionUi(false, sp.title, "", "Smart playlist · updates as you listen", sp.blurb, mix.tracks))
                }
                CollectionKind.HISTORY -> library.observeEvents(clock.now() - 90L * 86_400_000).collect { events ->
                    val tracks = library.tracks(events.map { it.trackId }.distinct()).associateBy { it.id }
                    val fmt = java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL)
                    val days = events.sortedByDescending { it.startedAt }
                        .groupBy { java.time.Instant.ofEpochMilli(it.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
                        .map { (d, ev) -> fmt.format(java.util.Date.from(d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant())) to ev.mapNotNull { tracks[it.trackId] }.distinctBy { it.id } }
                    set(CollectionUi(false, "History", "", "Played through Arnav Music · last 90 days", tracks = days.flatMap { it.second }.distinctBy { it.id }, historyDays = days))
                }
            }
        }
    }

    private fun titleFromCache(tracks: List<Track>) = tracks.firstOrNull()?.let { "Playlist with ${it.artist}" } ?: "YouTube playlist"

    private fun set(ui: CollectionUi) {
        val total = ui.tracks.sumOf { it.durationMs ?: 0L }
        val waiting = ui.pending.count { !it.failed }
        _ui.value = ui.copy(
            subtitle = "${ui.tracks.size} ${if (ui.tracks.size == 1) "song" else "songs"}" +
                (if (total > 0) " · " + com.arnav.music.domain.format.Formatters.longDuration(total) else "") +
                (if (waiting > 0) " · $waiting waiting to match" else ""),
        )
    }

    fun visibleTracks(ui: CollectionUi, q: String, s: SmartSort): List<Track> {
        val f = if (q.isBlank()) ui.tracks else ui.tracks.filter { it.title.contains(q, true) || it.artist.contains(q, true) }
        return when (s) {
            SmartSort.DEFAULT -> f
            SmartSort.TITLE -> f.sortedBy { it.title.lowercase() }
            SmartSort.ENERGY_UP -> f.sortedBy { it.energy ?: 0.5f }
            SmartSort.VARIETY -> {
                // Round-robin across artists so no artist plays twice in a row when avoidable.
                val buckets = f.groupBy { it.artistKey }.values.map { ArrayDeque(it) }.sortedByDescending { it.size }
                buildList { while (buckets.any { it.isNotEmpty() }) buckets.forEach { b -> b.removeFirstOrNull()?.let(::add) } }
            }
        }
    }

    fun removeTrack(t: Track) = viewModelScope.launch { if (kind == CollectionKind.PLAYLIST) library.removeFromPlaylist(id, t.id) }
    fun rename(name: String, description: String) = viewModelScope.launch { library.renamePlaylist(id, name, description) }
    fun togglePin() = viewModelScope.launch { library.togglePin(id) }
    fun delete() = viewModelScope.launch { library.deletePlaylist(id) }
    fun saveYouTube() = viewModelScope.launch {
        val u = _ui.value
        library.saveYouTubePlaylist(Playlist(id, u.title, u.description, PlaylistKind.YOUTUBE, u.tracks.firstOrNull()?.artworkUrl, u.tracks.size))
        _ui.update { it.copy(savedToLibrary = true) }
    }
    fun duplicateAsArnav() = viewModelScope.launch { library.createPlaylist(_ui.value.title, tracks = _ui.value.tracks) }

    // ---- Imported playlists ----

    /** Searches YouTube for one waiting song now (the user tapped it). [onResult] gets a message to show. */
    fun matchPending(row: PendingMatchEntity, onResult: (String) -> Unit) {
        if (row.failed || row.id in _matching.value) return
        _matching.update { it + row.id }
        viewModelScope.launch {
            val result = runCatching { matcher.matchNow(row.id) }.getOrDefault(MatchNowResult.Offline)
            _matching.update { it - row.id }
            when (result) {
                is MatchNowResult.Matched -> onResult("Matched “${row.title}”")
                MatchNowResult.NoMatch -> onResult("No good match for “${row.title}” on YouTube.")
                MatchNowResult.QuotaLimited -> onResult("YouTube's free daily search limit is used up. This song will be tried again tomorrow.")
                MatchNowResult.Offline -> onResult("Couldn't reach YouTube. It'll be matched automatically later.")
                MatchNowResult.Gone -> Unit
            }
        }
    }

    fun removePending(row: PendingMatchEntity) = viewModelScope.launch { matcher.dismiss(row.id) }

    /**
     * Replaces this playlist's songs with the current YouTube version. The token is used for this
     * call only and never stored.
     */
    fun refreshFromYouTube(token: String?, onResult: (String) -> Unit) {
        val remote = remoteRef ?: return
        if (token.isNullOrBlank()) { onResult("Google didn't return access. Try again."); return }
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            runCatching { youtubeImporter.refresh(token, listOf(remote)) }
                .onSuccess { s ->
                    onResult(
                        if (s.playlists > 0) "Refreshed from YouTube · ${s.tracks} ${if (s.tracks == 1) "song" else "songs"}"
                        else "This playlist is no longer available on YouTube, so it was left as it was.",
                    )
                }
                .onFailure { onResult(describeYouTubeError(it)) }
            _refreshing.value = false
        }
    }

    // ---- Artist ----
    private val _artist = MutableStateFlow(CollectionUi())
    val artist: StateFlow<CollectionUi> = _artist.asStateFlow()
    fun loadArtist(name: String) = viewModelScope.launch {
        val key = ArtistKey.of(name)
        val known = library.tracksByArtist(key) + library.localTracks.value.filter { it.artistKey == key }
        _artist.value = CollectionUi(loading = true, title = name, kindLabel = "Artist", tracks = known.distinctBy { it.id })
        val cached = youtube.cached(name, SearchFilter.TRACKS)
        val remote = cached ?: youtube.search(name, SearchFilter.TRACKS).getOrNull()
        val fromSearch = remote?.tracks.orEmpty().filter { it.artistKey == key || it.artist.contains(name, true) }
        val all = (known + fromSearch).distinctBy { it.id }
        _artist.value = CollectionUi(false, name, "${all.size} songs", "Artist", tracks = all, youtube = fromSearch.isNotEmpty())
    }
}
