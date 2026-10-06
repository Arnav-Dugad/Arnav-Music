package com.arnav.music.core.ai

import com.arnav.music.core.common.Clock
import com.arnav.music.core.common.Log
import com.arnav.music.core.db.AiCacheDao
import com.arnav.music.core.db.AiCacheEntity
import com.arnav.music.core.diagnostics.UsageMeter
import com.arnav.music.core.firebase.FirebaseGate
import com.arnav.music.core.firebase.RemoteConfigRepository
import com.arnav.music.core.settings.SettingsRepository
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest

enum class AiUnavailableReason { DISABLED_BY_USER, NOT_CONFIGURED, DAILY_LIMIT, THROTTLED, QUOTA, APP_CHECK, OFFLINE, TIMEOUT, MALFORMED, ERROR }

sealed interface AiOutcome {
    data class Ok(val text: String, val cached: Boolean) : AiOutcome
    data class Unavailable(val reason: AiUnavailableReason) : AiOutcome
}

/**
 * The only door to Gemini (Firebase AI Logic, Gemini Developer API free tier).
 * Guarantees: user consent, daily cap, min interval throttle, response cache keyed by prompt
 * version, timeout, no retries on quota errors. Callers always have a local fallback.
 */
class AiGateway(
    private val gate: FirebaseGate,
    private val remote: RemoteConfigRepository,
    private val settings: SettingsRepository,
    private val cache: AiCacheDao,
    private val usage: UsageMeter,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    @Volatile private var lastCallAt = 0L
    @Volatile private var quotaBlockedUntil = 0L
    @Volatile private var appCheckBlockedUntil = 0L
    /** Last Gemini failure, shown in Settings → Arnav AI for diagnosis (no prompts or user data). */
    @Volatile var lastError: String? = null
        private set

    fun availability(): AiUnavailableReason? {
        val s = settings.settings.value
        return when {
            !s.aiEnabled -> AiUnavailableReason.DISABLED_BY_USER
            !gate.isAvailable || !remote.tunables.value.aiEnabled -> AiUnavailableReason.NOT_CONFIGURED
            usage.state.value.aiRequests >= s.dailyAiLimit -> AiUnavailableReason.DAILY_LIMIT
            clock.now() < quotaBlockedUntil -> AiUnavailableReason.QUOTA
            clock.now() < appCheckBlockedUntil -> AiUnavailableReason.APP_CHECK
            else -> null
        }
    }

    suspend fun generate(prompt: String, promptVersion: String, json: Boolean, cacheTtlMs: Long = 7L * 86_400_000): AiOutcome {
        val key = sha256(promptVersion + "\n" + prompt)
        cache.get(key, promptVersion)?.let { hit ->
            if (clock.now() - hit.createdAt < cacheTtlMs) {
                usage.aiCacheHit()
                return AiOutcome.Ok(hit.response, cached = true)
            }
        }
        availability()?.let { return AiOutcome.Unavailable(it) }
        val t = remote.tunables.value

        return mutex.withLock {
            if (clock.now() - lastCallAt < t.aiMinIntervalMs) return@withLock AiOutcome.Unavailable(AiUnavailableReason.THROTTLED)
            lastCallAt = clock.now()
            usage.aiRequest()
            try {
                val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                    modelName = t.aiModel,
                    generationConfig = generationConfig {
                        temperature = t.aiTemperature
                        maxOutputTokens = t.aiMaxOutputTokens
                        if (json) responseMimeType = "application/json"
                    },
                )
                val text = withTimeout(t.aiTimeoutMs) { model.generateContent(prompt).text }
                if (text.isNullOrBlank()) AiOutcome.Unavailable(AiUnavailableReason.MALFORMED)
                else {
                    cache.put(AiCacheEntity(key, promptVersion, text, clock.now()))
                    AiOutcome.Ok(text, cached = false)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                AiOutcome.Unavailable(AiUnavailableReason.TIMEOUT)
            } catch (e: Exception) {
                val msg = (e.message ?: "").lowercase()
                lastError = "${e.javaClass.simpleName}: ${e.message?.take(160) ?: "no message"}"
                Log.w("AI call failed", e)
                if ("app check" in msg || "appcheck" in msg || "attestation" in msg || "app-check" in msg) {
                    // Enforced App Check rejected this install (e.g. sideloaded APK + Play Integrity).
                    // Stop trying for 6 hours; the on-device engine answers meanwhile.
                    appCheckBlockedUntil = clock.now() + 6 * 60 * 60_000L
                    AiOutcome.Unavailable(AiUnavailableReason.APP_CHECK)
                } else if ("quota" in msg || "429" in msg || "resource_exhausted" in msg || "rate" in msg) {
                    // Back off for an hour; never hammer the free tier.
                    quotaBlockedUntil = clock.now() + 60 * 60_000L
                    AiOutcome.Unavailable(AiUnavailableReason.QUOTA)
                } else if ("unable to resolve" in msg || "network" in msg || "timeout" in msg) {
                    AiOutcome.Unavailable(AiUnavailableReason.OFFLINE)
                } else AiOutcome.Unavailable(AiUnavailableReason.ERROR)
            }
        }
    }

    suspend fun clearCache() = cache.clear()

    /** Counted when a request had to be answered by the on-device engine. */
    fun noteFallback() = usage.aiFallback()

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
