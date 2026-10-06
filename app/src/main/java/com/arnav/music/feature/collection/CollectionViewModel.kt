package com.arnav.music.feature.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.common.Clock
import com.arnav.music.core.repo.IntelligenceRepository
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.core.youtube.YouTubeRepository
import com.arnav.music.domain.intelligence.SmartPlaylist
import com.arnav.music.domain.model.ArtistKey
import com.arnav.music.domain.model.Playlist
import com.arnav.music.domain.model.PlaylistKind
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.provider.MusicError
import com.arnav.music.domain.provider.SearchFilter
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
)

class CollectionViewModel(
    private val library: LibraryRepository,
    private val intelligence: IntelligenceRepository,
    private val youtube: YouTubeRepository,
    private val clock: Clock,
) : ViewModel() {
    private val _ui = MutableStateFlow(CollectionUi())
    val ui: StateFlow<CollectionUi> = _ui.asStateFlow()
    val filter = MutableStateFlow("")
    val sort = MutableStateFlow(SmartSort.DEFAULT)
    private var job: Job? = null
    private var kind: CollectionKind = CollectionKind.LIKED
    private var id: String = ""

    fun load(kind: CollectionKind, id: String) {
        if (job != null && this.kind == kind && this.id == id) return
        this.kind = kind; this.id = id
        job?.cancel()
        job = viewModelScope.launch {
            when (kind) {
                CollectionKind.LIKED -> library.likedTracks.collect { set(CollectionUi(false, "Liked songs", "", "Your favourites", tracks = it)) }
                CollectionKind.LOCAL -> library.localTracks.collect { set(CollectionUi(false, "On this device", "", "Background playback · offline", tracks = it)) }
                CollectionKind.PLAYLIST -> combine(library.playlist(id), library.playlistTracks(id)) { p, t -> p to t }.collect { (p, t) ->
                    if (p == null || p.deleted) set(CollectionUi(false, "Playlist not found", error = MusicError.Unavailable))
                    else set(CollectionUi(false, p.name, "", if (p.kind == PlaylistKind.YOUTUBE.name) "YouTube playlist" else "Arnav playlist", p.description, t, editable = p.kind == PlaylistKind.ARNAV.name, pinned = p.pinned))
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
        _ui.value = ui.copy(subtitle = "${ui.tracks.size} ${if (ui.tracks.size == 1) "song" else "songs"}" + if (total > 0) " · " + com.arnav.music.domain.format.Formatters.longDuration(total) else "")
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
