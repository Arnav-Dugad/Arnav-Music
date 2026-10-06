package com.arnav.music.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.ai.AiGateway
import com.arnav.music.core.ai.AiUnavailableReason
import com.arnav.music.core.firebase.Analytics
import com.arnav.music.core.repo.IntelligenceRepository
import com.arnav.music.core.repo.SessionResult
import com.arnav.music.core.settings.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class BuildStep(val label: String) { UNDERSTAND("Understanding your request"), FIND("Finding real, playable songs"), SHAPE("Shaping the energy curve") }

sealed interface AiUi {
    data object Idle : AiUi
    data class Building(val prompt: String, val step: BuildStep) : AiUi
    data class Ready(val prompt: String, val result: SessionResult) : AiUi
    data class Empty(val prompt: String, val result: SessionResult) : AiUi
}

class ArnavAiViewModel(
    private val intelligence: IntelligenceRepository,
    private val ai: AiGateway,
    private val analytics: Analytics,
    settings: SettingsRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow<AiUi>(AiUi.Idle)
    val ui: StateFlow<AiUi> = _ui.asStateFlow()
    val history = MutableStateFlow<List<String>>(emptyList())
    val settings = settings.settings

    /** Whether Gemini is currently usable, or the on-device engine will answer. */
    fun mode(): AiUnavailableReason? = ai.availability()

    fun build(prompt: String) {
        val p = prompt.trim().take(300)
        if (p.isBlank()) return
        analytics.log(Analytics.Event.AI_INVOKED)
        history.value = (listOf(p) + history.value.filter { it != p }).take(6)
        viewModelScope.launch {
            _ui.value = AiUi.Building(p, BuildStep.UNDERSTAND)
            val job = launch {
                // Step labels mirror the real pipeline phases; they advance while work happens.
                delay(700); _ui.value = AiUi.Building(p, BuildStep.FIND)
                delay(900); _ui.value = AiUi.Building(p, BuildStep.SHAPE)
            }
            val result = runCatching { intelligence.buildSession(p) }.getOrNull()
            job.cancel()
            _ui.value = when {
                result == null -> AiUi.Idle
                result.session.tracks.isEmpty() -> AiUi.Empty(p, result)
                else -> AiUi.Ready(p, result)
            }
        }
    }

    fun reset() { _ui.value = AiUi.Idle }
    fun explain(reason: com.arnav.music.domain.intelligence.Reason, track: com.arnav.music.domain.model.Track) = intelligence.explain(reason, track)
}
