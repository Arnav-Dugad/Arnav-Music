package com.arnav.music.feature.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.ai.AiGateway
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.diagnostics.UsageMeter
import com.arnav.music.core.firebase.Analytics
import com.arnav.music.core.firebase.AuthRepository
import com.arnav.music.core.firebase.AuthResult
import com.arnav.music.core.firebase.CloudSync
import com.arnav.music.core.perf.PerformanceManager
import com.arnav.music.core.repo.IntelligenceRepository
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.core.repo.SearchRepository
import com.arnav.music.core.security.SecureStore
import com.arnav.music.core.settings.AppSettings
import com.arnav.music.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StorageInfo(val databaseKb: Long = 0, val artworkCacheKb: Long = 0, val httpCacheKb: Long = 0, val knownTracks: Int = 0, val aiCached: Int = 0, val searchCached: Int = 0)

class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val secure: SecureStore,
    private val auth: AuthRepository,
    private val sync: CloudSync,
    private val library: LibraryRepository,
    private val search: SearchRepository,
    private val ai: AiGateway,
    val usage: UsageMeter,
    val perf: PerformanceManager,
    private val db: ArnavDatabase,
    private val analytics: Analytics,
    private val intelligence: IntelligenceRepository,
    val updates: com.arnav.music.core.update.UpdateManager,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepo.settings
    val budget = perf.budget
    val syncStatus = sync.status
    val user = auth.currentUser.stateIn(viewModelScope, SharingStarted.Eagerly, auth.current())
    val cloudAvailable: Boolean get() = auth.isAvailable
    val lastSyncedAt: Long get() = sync.lastSyncedAt
    val lastAiError: String? get() = ai.lastError

    private val _apiKeyPresent = MutableStateFlow(!secure.get(SecureStore.YOUTUBE_API_KEY).isNullOrBlank())
    val apiKeyPresent: StateFlow<Boolean> = _apiKeyPresent.asStateFlow()
    val builtInKey: Boolean = com.arnav.music.BuildConfig.YOUTUBE_API_KEY.isNotBlank()

    private val _storage = MutableStateFlow(StorageInfo())
    val storage: StateFlow<StorageInfo> = _storage.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { settingsRepo.update(transform) }
    fun consumeNotice() { _notice.value = null }

    fun saveApiKey(key: String) {
        val k = key.trim()
        secure.put(SecureStore.YOUTUBE_API_KEY, k.ifBlank { null })
        _apiKeyPresent.value = k.isNotBlank()
        _notice.value = if (k.isBlank()) "YouTube key removed" else "YouTube key saved securely on this device"
    }

    fun loadStorage(context: android.content.Context) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        fun sizeKb(f: java.io.File): Long = if (!f.exists()) 0 else f.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024
        _storage.value = StorageInfo(
            databaseKb = context.getDatabasePath(ArnavDatabase.NAME).length() / 1024,
            artworkCacheKb = sizeKb(java.io.File(context.cacheDir, "artwork")),
            httpCacheKb = sizeKb(java.io.File(context.cacheDir, "http")),
            knownTracks = db.tracks().count(),
            aiCached = db.aiCache().count(),
            searchCached = db.search().count(),
        )
    }

    fun syncNow() = viewModelScope.launch { sync.syncNow(); _notice.value = "Sync finished" }

    fun clearSearchHistory() = viewModelScope.launch { search.clearHistory(); search.clearCache(); _notice.value = "Search history and saved results cleared" }
    fun clearListeningHistory() = viewModelScope.launch { library.clearHistory(); intelligence.invalidate(); _notice.value = "Listening history cleared" }
    fun deleteAiPersonalization() = viewModelScope.launch {
        ai.clearCache()
        settingsRepo.update { it.copy(aiPersonalization = false) }
        _notice.value = "AI personalization deleted and turned off"
    }
    fun disconnectYouTube() { saveApiKey(""); _notice.value = "YouTube disconnected. Saved results remain until you clear them." }

    fun deleteCloudProfile() = viewModelScope.launch {
        _busy.value = "Deleting cloud data…"
        _notice.value = if (sync.deleteCloudProfile().isSuccess) "Cloud profile deleted" else "Couldn't reach the cloud. Try again when online."
        _busy.value = null
    }

    fun deleteAccount(activity: Activity?) = viewModelScope.launch {
        _busy.value = "Deleting your account…"
        val cloud = sync.deleteCloudProfile()
        val result = if (cloud.isSuccess) auth.deleteAccount() else AuthResult.Failure("Couldn't delete cloud data. Check your connection.")
        _notice.value = when (result) {
            AuthResult.Success -> { auth.signOut(activity); "Your account and cloud data are deleted" }
            is AuthResult.Failure -> result.message
            AuthResult.Cancelled -> null
        }
        _busy.value = null
    }

    fun signOut(activity: Activity?) = viewModelScope.launch { auth.signOut(activity); _notice.value = "Signed out. Your library stays on this device." }
    fun resendVerification() = viewModelScope.launch { _notice.value = if (auth.resendVerification() == AuthResult.Success) "Verification email sent" else "Couldn't send the email" }
    fun setAnalytics(on: Boolean) = viewModelScope.launch { settingsRepo.update { it.copy(analytics = on) }; analytics.setEnabled(on) }
    fun resetOnboarding() = viewModelScope.launch { settingsRepo.update { it.copy(onboardingDone = false) } }
}
