package com.arnav.music.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.common.Clock
import com.arnav.music.core.common.NetworkMonitor
import com.arnav.music.core.repo.HomeSection
import com.arnav.music.core.repo.IntelligenceRepository
import com.arnav.music.core.repo.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val greeting: String = "",
    val sections: List<HomeSection> = emptyList(),
)

class HomeViewModel(
    private val intelligence: IntelligenceRepository,
    library: LibraryRepository,
    network: NetworkMonitor,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState(greeting = IntelligenceRepository.greeting(clock.now())))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Recompose the home when listening, likes, local files or connectivity change —
            // debounced so playback doesn't constantly reshuffle the page.
            combine(library.eventCount, library.likedIds, library.localTracks, network.isOnline) { a, b, c, d -> listOf(a, b.size, c.size, d) }
                .distinctUntilChanged()
                .debounce(600)
                .collect { refresh() }
        }
    }

    fun refresh() = viewModelScope.launch {
        intelligence.invalidate()
        val sections = runCatching { intelligence.composeHome() }.getOrDefault(_state.value.sections)
        _state.value = HomeUiState(false, IntelligenceRepository.greeting(clock.now()), sections)
    }
}
