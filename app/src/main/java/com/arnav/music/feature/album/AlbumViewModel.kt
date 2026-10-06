package com.arnav.music.feature.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.domain.library.AlbumDisc
import com.arnav.music.domain.library.AlbumSummary
import com.arnav.music.domain.library.Albums
import com.arnav.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AlbumUi(
    val loading: Boolean = true,
    val album: AlbumSummary? = null,
    val discs: List<AlbumDisc> = emptyList(),
) {
    /** Songs in play order (disc, then track number). */
    val tracks: List<Track> get() = album?.tracks.orEmpty()
}

/** One on-device album (MediaStore album id), with tag overrides already applied. */
class AlbumViewModel(private val library: LibraryRepository) : ViewModel() {
    private val _ui = MutableStateFlow(AlbumUi())
    val ui: StateFlow<AlbumUi> = _ui.asStateFlow()
    private var albumId: String? = null
    private var job: Job? = null

    fun load(id: String) {
        if (albumId == id && job != null) return
        albumId = id
        job?.cancel()
        job = viewModelScope.launch {
            publish(id, runCatching { library.localTracksSnapshot() }.getOrDefault(emptyList()))
            // Follows MediaStore changes and tag edits; the initial empty value is skipped.
            library.localTracks.collect { all -> if (all.isNotEmpty()) publish(id, all) }
        }
    }

    private suspend fun publish(id: String, all: List<Track>) {
        val ui = withContext(Dispatchers.Default) {
            val mine = all.filter { it.albumId == id }
            if (mine.isEmpty()) AlbumUi(loading = false)
            else {
                val summary = Albums.summary(id, mine)
                AlbumUi(loading = false, album = summary, discs = Albums.discs(summary.tracks))
            }
        }
        _ui.value = ui
    }
}
