package com.arnav.music.core.repo

import com.arnav.music.core.ai.AiGateway
import com.arnav.music.core.ai.AiOutcome
import com.arnav.music.core.ai.AiUnavailableReason
import com.arnav.music.core.common.Clock
import com.arnav.music.core.common.NetworkMonitor
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.core.youtube.YouTubeRepository
import com.arnav.music.domain.ai.AiJson
import com.arnav.music.domain.ai.PromptLibrary
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.intelligence.BuiltSession
import com.arnav.music.domain.intelligence.ConstellationBuilder
import com.arnav.music.domain.intelligence.ConstellationGraph
import com.arnav.music.domain.intelligence.InsightsEngine
import com.arnav.music.domain.intelligence.LocalIntentEngine
import com.arnav.music.domain.intelligence.Reason
import com.arnav.music.domain.intelligence.Recap
import com.arnav.music.domain.intelligence.RecapPeriod
import com.arnav.music.domain.intelligence.Recommender
import com.arnav.music.domain.intelligence.SessionBuilder
import com.arnav.music.domain.intelligence.SessionConstraints
import com.arnav.music.domain.intelligence.SmartPlaylist
import com.arnav.music.domain.intelligence.SmartPlaylistEngine
import com.arnav.music.domain.intelligence.TasteDna
import com.arnav.music.domain.intelligence.TasteProfile
import com.arnav.music.domain.intelligence.TasteProfileBuilder
import com.arnav.music.domain.intelligence.TimeMachineInsight
import com.arnav.music.domain.model.Moment
import com.arnav.music.domain.model.Moments
import com.arnav.music.domain.model.Mood
import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import com.arnav.music.domain.provider.SearchFilter
import com.arnav.music.domain.quota.QuotaState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** A home section. The composer decides which few to show, and in what order. */
sealed interface HomeSection {
    val key: String
    data class ContinueListening(val tracks: List<Track>) : HomeSection { override val key = "continue" }
    data class MomentsRow(val moments: List<Moment>, val featured: Moment) : HomeSection { override val key = "moments" }
    data class MadeForYou(val mixes: List<SmartMix>) : HomeSection { override val key = "made" }
    data class TrackShelf(override val key: String, val title: String, val subtitle: String?, val tracks: List<Track>, val reasons: Map<TrackId, Reason> = emptyMap()) : HomeSection
    data class TimeMachine(val insight: TimeMachineInsight, val tracks: List<Track>) : HomeSection { override val key = "tm_${insight.kind}" }
    data class StartHere(val moods: List<Mood>) : HomeSection { override val key = "start" }
}

data class SmartMix(val kind: SmartPlaylist, val tracks: List<Track>) {
    val artwork: List<String> get() = tracks.mapNotNull { it.artworkUrl }.distinct().take(4)
}

data class SessionResult(
    val session: BuiltSession,
    val usedAi: Boolean,
    val aiUnavailable: AiUnavailableReason?,
    val explanation: String?,
    val searchedRemotely: Int,
)

class IntelligenceRepository(
    private val library: LibraryRepository,
    private val youtube: YouTubeRepository,
    private val ai: AiGateway,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
    private val clock: Clock,
) {
    private val recommender = Recommender()
    private val builder = SessionBuilder(recommender)
    private val profileLock = Mutex()
    private var cachedProfile: Pair<Long, TasteProfile>? = null
    private val zone: ZoneId get() = ZoneId.systemDefault()

    suspend fun profile(force: Boolean = false): TasteProfile = profileLock.withLock {
        val now = clock.now()
        cachedProfile?.let { (at, p) -> if (!force && now - at < 60_000) return@withLock p }
        val events = library.events(now - 365L * DAY)
        val ids = (events.map { it.trackId } + library.likedIds.value).distinct()
        val tracks = library.tracks(ids).associateBy { it.id }
        val p = TasteProfileBuilder.build(events, tracks, library.likedIds.value, now, zone)
        cachedProfile = now to p
        p
    }

    suspend fun smartMix(kind: SmartPlaylist): SmartMix {
        val now = clock.now()
        val ids = SmartPlaylistEngine.compute(kind, library.events(now - 2 * 365L * DAY), library.likedIds.value, now, zone)
        return SmartMix(kind, library.tracks(ids))
    }

    suspend fun smartMixes(): List<SmartMix> {
        val now = clock.now()
        val events = library.events(now - 2 * 365L * DAY)
        val liked = library.likedIds.value
        return SmartPlaylist.entries.map { kind -> kind to SmartPlaylistEngine.compute(kind, events, liked, now, zone) }
            .filter { it.second.size >= 3 }
            .map { (kind, ids) -> SmartMix(kind, library.tracks(ids)) }
            .filter { it.tracks.size >= 3 }
    }

    /** Composes a personal, prioritised home (5–8 sections, never a wall of rows). */
    suspend fun composeHome(): List<HomeSection> = coroutineScope {
        val now = clock.now()
        val hour = Instant.ofEpochMilli(now).atZone(zone).hour
        val profileD = async { profile() }
        val mixesD = async { smartMixes() }
        val recentD = async { library.events(now - 60L * DAY) }
        val profile = profileD.await()
        val mixes = mixesD.await()
        val recentEvents = recentD.await()
        val out = ArrayList<HomeSection>()

        val recentIds = recentEvents.sortedByDescending { it.startedAt }.map { it.trackId }.distinct().take(12)
        val recent = library.tracks(recentIds)
        if (recent.isNotEmpty()) out += HomeSection.ContinueListening(recent)

        val featured = featuredMoment(hour, settings.settings.value.selectedMoods)
        out += HomeSection.MomentsRow(listOf(featured) + Moments.all.filter { it.id != featured.id }, featured)

        if (mixes.isNotEmpty()) out += HomeSection.MadeForYou(mixes.take(6))

        if (profile.isCold) {
            val moods = settings.settings.value.selectedMoods.mapNotNull { m -> Mood.entries.firstOrNull { it.name == m } }
            out += HomeSection.StartHere(moods.ifEmpty { listOf(Mood.UPBEAT, Mood.CHILL, Mood.FOCUS, Mood.ENERGETIC) })
        }

        // "Because you played …" — strongest recent artist, deterministic picks from known catalog.
        val topRecentArtist = recentEvents.groupBy { it.artistKey }.maxByOrNull { (_, v) -> v.sumOf { it.listenedMs } }?.key
        if (topRecentArtist != null) {
            val anchor = recent.firstOrNull { it.artistKey == topRecentArtist }
            val pool = library.allKnownTracks().filter { it.artistKey != topRecentArtist }
            val similar = pool.filter { t -> anchor != null && (t.genres.intersect(anchor.genres.toSet()).isNotEmpty() || (t.energy != null && anchor.energy != null && abs(t.energy!! - anchor.energy!!) < 0.12f)) }
            val ranked = recommender.rank(similar, profile, now, library.likedIds.value, anchor?.energy).take(12)
            if (anchor != null && ranked.size >= 4) out += HomeSection.TrackShelf("because", "Because you played ${anchor.artist}", "Similar energy and style", ranked.map { it.track }, ranked.associate { it.track.id to it.reason })
        }

        InsightsEngine.timeMachine(library.events(now - 400L * DAY), now, zone).firstOrNull()?.let { tm ->
            val tracks = library.tracks(tm.trackIds)
            if (tracks.size >= 2) out += HomeSection.TimeMachine(tm, tracks)
        }

        val local = library.localTracks.value
        if (local.isNotEmpty()) {
            val ranked = recommender.rank(local, profile, now, library.likedIds.value, discovery = 0.2f).take(14)
            out += HomeSection.TrackShelf("local", "From your device", "Plays in the background, offline", ranked.map { it.track })
        }

        if (network.currentlyOnline() || youtube.quotaState() != QuotaState.NORMAL) {
            youtube.trending().getOrNull()?.takeIf { it.isNotEmpty() }?.let { trending ->
                val ranked = recommender.rank(trending, profile, now, library.likedIds.value, discovery = 0.5f)
                out += HomeSection.TrackShelf("trending", "Trending in music", "From YouTube's popular music chart", ranked.map { it.track }.take(20))
            }
        }

        // Late night → moments first; morning → continue first. Light, honest prioritisation.
        val order = if (hour >= 21 || hour < 4) listOf("moments", "continue", "made", "because", "local", "start", "trending")
        else listOf("continue", "moments", "made", "start", "because", "local", "trending")
        out.sortedBy { s -> order.indexOf(s.key).let { if (it < 0) 50 else it } }.take(8)
    }

    fun featuredMoment(hour: Int, moods: Set<String>): Moment {
        val byTime = when (hour) {
            in 5..9 -> "golden_hour"
            in 10..16 -> "deep_focus"
            in 17..19 -> "golden_hour"
            in 20..22 -> "night_drive"
            else -> "late_night"
        }
        val moodPick = Moments.all.firstOrNull { m -> m.moods.any { it.name in moods } && (hour in 9..18) }
        return moodPick ?: Moments.byId(byTime) ?: Moments.all.first()
    }

    /** Arnav AI session: AI (optional) interprets → real search resolves → deterministic builder orders. */
    suspend fun buildSession(request: String): SessionResult {
        val now = clock.now()
        val profile = profile()
        val s = settings.settings.value
        val names = artistDisplayNames(profile)
        var aiReason: AiUnavailableReason? = null
        var explanation: String? = null
        var constraints: SessionConstraints = LocalIntentEngine.interpret(request, names.take(3))
        var usedAi = false

        if (s.aiEnabled) {
            val prompt = PromptLibrary.sessionPrompt(
                request,
                if (s.aiPersonalization) names else emptyList(),
                if (s.aiPersonalization) profile.genreAffinity.entries.sortedByDescending { it.value }.take(5).map { it.key } else emptyList(),
                Instant.ofEpochMilli(now).atZone(zone).hour,
            )
            when (val r = ai.generate(prompt, PromptLibrary.SESSION_VERSION, json = true)) {
                is AiOutcome.Ok -> AiJson.parseSession(r.text).onSuccess { parsed ->
                    constraints = parsed.toConstraints()
                    explanation = parsed.explanation?.take(140)
                    usedAi = true
                }.onFailure { aiReason = AiUnavailableReason.MALFORMED }
                is AiOutcome.Unavailable -> aiReason = r.reason
            }
        } else aiReason = AiUnavailableReason.DISABLED_BY_USER

        val (candidates, searched) = resolveCandidates(constraints, profile)
        val session = builder.build(constraints, candidates, profile, library.likedIds.value, now)
        library.remember(session.tracks)
        return SessionResult(session, usedAi, aiReason, explanation, searched)
    }

    suspend fun momentQueue(moment: Moment): BuiltSession {
        val profile = profile()
        val c = SessionConstraints(
            title = moment.title, durationMinutes = 60, energyTarget = moment.aesthetic.energy,
            moods = moment.moods.map { it.name.lowercase() }, searchQueries = moment.seedQueries.take(2),
            familiarity = 0.5f, discoveryRatio = 0.4f,
        )
        val (candidates, _) = resolveCandidates(c, profile)
        val s = builder.build(c, candidates, profile, library.likedIds.value, clock.now())
        library.remember(s.tracks)
        return s
    }

    private suspend fun resolveCandidates(c: SessionConstraints, profile: TasteProfile): Pair<List<Track>, Int> {
        val now = clock.now()
        val known = library.allKnownTracks().distinctBy { it.id }
        val moods = c.moodSet
        val avoid = c.avoidMoods.mapNotNull { a -> Mood.entries.firstOrNull { it.name.equals(a, true) } }.toSet()
        val maxEnergy = if (Mood.AGGRESSIVE in avoid) 0.85f else 1f

        val fromKnown = when {
            c.rediscover -> known.filter { t -> (profile.lastPlayedAt[t.id] ?: now).let { now - it > 30 * DAY } && (profile.trackPlayCounts[t.id] ?: 0) >= 1 }
            moods.isEmpty() -> known
            else -> known.filter { t -> t.energy == null || abs(t.energy!! - c.energyTarget) < 0.3f }
        }.filter { (it.energy ?: 0f) <= maxEnergy }

        val budget = when (youtube.quotaState()) {
            QuotaState.NORMAL -> 2
            QuotaState.CONSERVE -> 1
            QuotaState.EXHAUSTED -> 0
        }
        val queries = (c.seedArtists + c.searchQueries).distinct()
        var remote = 0
        val fromSearch = ArrayList<Track>()
        for (q in queries) {
            val cached = youtube.cached(q, SearchFilter.TRACKS)
            val res = when {
                cached != null -> cached
                remote < budget && network.currentlyOnline() -> youtube.search(q, SearchFilter.TRACKS).getOrNull()?.also { if (!it.fromCache) remote++ }
                else -> null
            }
            res?.tracks?.let { fromSearch += it }
            if (fromSearch.size > 120) break
        }
        return (fromSearch + fromKnown).filter { (it.energy ?: 0f) <= maxEnergy }.distinctBy { it.id } to remote
    }

    fun explain(reason: Reason, track: Track): String = when (reason) {
        Reason.ARTIST_RETURNING -> "Because you've been returning to ${track.artist} lately."
        Reason.SIMILAR_ENERGY -> "Similar energy to what you've been playing."
        Reason.GENRE_MATCH -> "Close to the styles you play most."
        Reason.FORGOTTEN_FAVORITE -> "A favourite you haven't played in a while."
        Reason.NEW_DISCOVERY -> "Something different from your usual rotation."
        Reason.HEAVY_ROTATION -> "In your heavy rotation."
        Reason.LIKED -> "From your liked songs."
        Reason.TIME_OF_DAY -> "Picked for this moment."
    }

    suspend fun recap(period: RecapPeriod): Recap = InsightsEngine.recap(period, library.events(clock.now() - (period.days + 1) * DAY - DAY), clock.now(), zone)

    suspend fun tasteDna(): TasteDna {
        val now = clock.now()
        val events = library.events(now - 365L * DAY)
        val years = library.tracks(events.map { it.trackId }.distinct()).mapNotNull { t -> t.year?.let { t.id to it } }.toMap()
        return InsightsEngine.tasteDna(profile(), events, years, now)
    }

    suspend fun constellation(): ConstellationGraph {
        val events = library.events(clock.now() - 365L * DAY)
        val tracks = library.tracks(events.map { it.trackId }.distinct())
        val names = tracks.associate { it.artistKey to it.artist }
        val genres = tracks.groupBy { it.artistKey }.mapValues { (_, v) -> v.flatMap { it.genres }.toSet() }
        return ConstellationBuilder.build(events, names, genres)
    }

    suspend fun eventsBetween(from: Long, to: Long): List<PlayEvent> = library.eventsBetween(from, to)

    suspend fun artistDisplayNames(profile: TasteProfile): List<String> {
        val keys = profile.artistAffinity.entries.sortedByDescending { it.value }.take(8).map { it.key }
        return keys.mapNotNull { k -> library.tracksByArtist(k).firstOrNull()?.artist }
    }

    fun invalidate() { cachedProfile = null }

    companion object {
        const val DAY = 86_400_000L
        fun greeting(now: Long): String = Formatters.greeting(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour)
    }
}
