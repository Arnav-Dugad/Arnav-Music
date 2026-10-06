package com.arnav.music.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

enum class ThemeMode { SYSTEM, LIGHT, DARK, OLED }
enum class AccentMode { ARTWORK, MATERIAL_YOU, PRESET }
enum class GlassLevel { OFF, SUBTLE, FULL }
enum class MotionLevel { FULL, REDUCED, MINIMAL }
enum class ArtworkMotion { OFF, SUBTLE, DYNAMIC }
enum class PerformanceMode { AUTOMATIC, MAXIMUM, BALANCED, BATTERY_SAVER }
enum class LibraryLayout { LIST, GRID, COMPACT }

/** User-facing preferences. Synced to the cloud profile only when the user opts in. */
@Serializable
data class AppSettings(
    val onboardingDone: Boolean = false,
    val guestMode: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentMode: AccentMode = AccentMode.ARTWORK,
    val presetAccent: Int = 0xFF8C7CFF.toInt(),
    val glass: GlassLevel = GlassLevel.SUBTLE,
    val motion: MotionLevel = MotionLevel.FULL,
    val artworkMotion: ArtworkMotion = ArtworkMotion.SUBTLE,
    val gyroParallax: Boolean = false,
    val haptics: Boolean = true,
    val performance: PerformanceMode = PerformanceMode.AUTOMATIC,
    val highContrast: Boolean = false,
    val reduceTransparency: Boolean = false,
    val libraryLayout: LibraryLayout = LibraryLayout.LIST,
    // Playback (local media)
    val gapless: Boolean = true,
    val fadeMs: Int = 400,
    val skipSilence: Boolean = false,
    val playbackSpeed: Float = 1f,
    val pauseOnDisconnect: Boolean = true,
    // Intelligence
    val aiEnabled: Boolean = true,
    val aiPersonalization: Boolean = true,
    val explanations: Boolean = true,
    val dailyAiLimit: Int = 40,
    // Data
    val cloudSync: Boolean = true,
    val analytics: Boolean = false,
    val youtubeDailyBudget: Int = 10_000,
    val weeklyRecapNotification: Boolean = false,
    val selectedMoods: Set<String> = emptySet(),
    val seedArtists: List<String> = emptyList(),
    val regionCode: String = "",
)

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private val store: DataStore<Preferences> get() = context.settingsStore

    private object K {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val guest = booleanPreferencesKey("guest_mode")
        val theme = stringPreferencesKey("theme")
        val accent = stringPreferencesKey("accent_mode")
        val presetAccent = intPreferencesKey("preset_accent")
        val glass = stringPreferencesKey("glass")
        val motion = stringPreferencesKey("motion")
        val artworkMotion = stringPreferencesKey("artwork_motion")
        val gyro = booleanPreferencesKey("gyro")
        val haptics = booleanPreferencesKey("haptics")
        val perf = stringPreferencesKey("performance")
        val highContrast = booleanPreferencesKey("high_contrast")
        val reduceTransparency = booleanPreferencesKey("reduce_transparency")
        val libraryLayout = stringPreferencesKey("library_layout")
        val gapless = booleanPreferencesKey("gapless")
        val fadeMs = intPreferencesKey("fade_ms")
        val skipSilence = booleanPreferencesKey("skip_silence")
        val speed = floatPreferencesKey("speed")
        val pauseOnDisconnect = booleanPreferencesKey("pause_disconnect")
        val ai = booleanPreferencesKey("ai_enabled")
        val aiPersonal = booleanPreferencesKey("ai_personal")
        val explanations = booleanPreferencesKey("explanations")
        val aiLimit = intPreferencesKey("ai_limit")
        val cloud = booleanPreferencesKey("cloud_sync")
        val analytics = booleanPreferencesKey("analytics")
        val ytBudget = intPreferencesKey("yt_budget")
        val recap = booleanPreferencesKey("weekly_recap")
        val moods = stringPreferencesKey("moods")
        val seeds = stringPreferencesKey("seed_artists")
        val region = stringPreferencesKey("region")
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    private val _loaded = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** False until DataStore has been read once — the splash screen waits on this. */
    val loaded: StateFlow<Boolean> = _loaded

    val settings: StateFlow<AppSettings> = store.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { p ->
            val d = AppSettings()
            AppSettings(
                onboardingDone = p[K.onboarding] ?: d.onboardingDone,
                guestMode = p[K.guest] ?: d.guestMode,
                themeMode = p.enum(K.theme, d.themeMode),
                accentMode = p.enum(K.accent, d.accentMode),
                presetAccent = p[K.presetAccent] ?: d.presetAccent,
                glass = p.enum(K.glass, d.glass),
                motion = p.enum(K.motion, d.motion),
                artworkMotion = p.enum(K.artworkMotion, d.artworkMotion),
                gyroParallax = p[K.gyro] ?: d.gyroParallax,
                haptics = p[K.haptics] ?: d.haptics,
                performance = p.enum(K.perf, d.performance),
                highContrast = p[K.highContrast] ?: d.highContrast,
                reduceTransparency = p[K.reduceTransparency] ?: d.reduceTransparency,
                libraryLayout = p.enum(K.libraryLayout, d.libraryLayout),
                gapless = p[K.gapless] ?: d.gapless,
                fadeMs = p[K.fadeMs] ?: d.fadeMs,
                skipSilence = p[K.skipSilence] ?: d.skipSilence,
                playbackSpeed = p[K.speed] ?: d.playbackSpeed,
                pauseOnDisconnect = p[K.pauseOnDisconnect] ?: d.pauseOnDisconnect,
                aiEnabled = p[K.ai] ?: d.aiEnabled,
                aiPersonalization = p[K.aiPersonal] ?: d.aiPersonalization,
                explanations = p[K.explanations] ?: d.explanations,
                dailyAiLimit = p[K.aiLimit] ?: d.dailyAiLimit,
                cloudSync = p[K.cloud] ?: d.cloudSync,
                analytics = p[K.analytics] ?: d.analytics,
                youtubeDailyBudget = p[K.ytBudget] ?: d.youtubeDailyBudget,
                weeklyRecapNotification = p[K.recap] ?: d.weeklyRecapNotification,
                selectedMoods = p[K.moods]?.split('|')?.filter { it.isNotBlank() }?.toSet() ?: d.selectedMoods,
                seedArtists = p[K.seeds]?.split('|')?.filter { it.isNotBlank() } ?: d.seedArtists,
                regionCode = p[K.region] ?: d.regionCode,
            )
        }
        .onEach { _loaded.value = true }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val s = transform(settings.value)
            p[K.onboarding] = s.onboardingDone
            p[K.guest] = s.guestMode
            p[K.theme] = s.themeMode.name
            p[K.accent] = s.accentMode.name
            p[K.presetAccent] = s.presetAccent
            p[K.glass] = s.glass.name
            p[K.motion] = s.motion.name
            p[K.artworkMotion] = s.artworkMotion.name
            p[K.gyro] = s.gyroParallax
            p[K.haptics] = s.haptics
            p[K.perf] = s.performance.name
            p[K.highContrast] = s.highContrast
            p[K.reduceTransparency] = s.reduceTransparency
            p[K.libraryLayout] = s.libraryLayout.name
            p[K.gapless] = s.gapless
            p[K.fadeMs] = s.fadeMs
            p[K.skipSilence] = s.skipSilence
            p[K.speed] = s.playbackSpeed
            p[K.pauseOnDisconnect] = s.pauseOnDisconnect
            p[K.ai] = s.aiEnabled
            p[K.aiPersonal] = s.aiPersonalization
            p[K.explanations] = s.explanations
            p[K.aiLimit] = s.dailyAiLimit
            p[K.cloud] = s.cloudSync
            p[K.analytics] = s.analytics
            p[K.ytBudget] = s.youtubeDailyBudget
            p[K.recap] = s.weeklyRecapNotification
            p[K.moods] = s.selectedMoods.joinToString("|")
            p[K.seeds] = s.seedArtists.joinToString("|")
            p[K.region] = s.regionCode
        }
    }

    suspend fun reset() { store.edit { it.clear() } }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
