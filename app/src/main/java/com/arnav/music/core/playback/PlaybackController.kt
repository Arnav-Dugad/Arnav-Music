package com.arnav.music.core.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.arnav.music.core.common.Clock
import com.arnav.music.core.common.Log
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.PlaybackCapabilities
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.queue.QueueState
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

enum class Engine { NONE, LOCAL, YOUTUBE }
enum class RepeatMode { OFF, ALL, ONE }

sealed interface PlaybackIssue {
    data class Unavailable(val title: String) : PlaybackIssue
    data object YouTubePausedInBackground : PlaybackIssue
    data object NetworkLost : PlaybackIssue
    data object NeedsNotificationPermission : PlaybackIssue
}

sealed interface SleepTimer {
    data class Countdown(val endsAt: Long, val totalMs: Long) : SleepTimer
    data object EndOfTrack : SleepTimer
    data object EndOfQueue : SleepTimer
}

data class PlayerState(
    val queue: QueueState = QueueState(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val engine: Engine = Engine.NONE,
    val capabilities: PlaybackCapabilities = PlaybackCapabilities.LocalMedia,
    val repeat: RepeatMode = RepeatMode.OFF,
    val issue: PlaybackIssue? = null,
    val sleep: SleepTimer? = null,
) {
    val current: Track? get() = queue.current?.track
}

data class Progress(val positionMs: Long = 0L, val durationMs: Long = 0L) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * Single source of truth for playback. The UI talks only to this class and reads
 * [PlaybackCapabilities] — it never needs to know which engine is underneath.
 */
class PlaybackController(
    private val context: Context,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val scope: CoroutineScope,
    val youtube: YouTubeEngine,
) : YouTubeEngine.Events {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var controller: MediaController? = null
    private var connecting = false
    private var pendingLocal: (() -> Unit)? = null
    /** Index range of the queue currently loaded into ExoPlayer (contiguous local tracks). */
    private var localRunStart = 0
    private var progressJob: Job? = null
    private var sleepJob: Job? = null
    private var persistJob: Job? = null
    private val prefs = context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // Listening recorder for the current track.
    private var sessionTrack: Track? = null
    private var sessionStartedAt = 0L
    private var sessionListenedMs = 0L
    private var lastTickAt = 0L

    init {
        youtube.events = this
        restoreQueue()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // YouTube embeds may not play hidden. Pause honestly and explain.
                if (_state.value.engine == Engine.YOUTUBE && _state.value.isPlaying) {
                    youtube.pause()
                    _state.update { it.copy(isPlaying = false, issue = PlaybackIssue.YouTubePausedInBackground) }
                }
            }
        })
    }

    // region Public API

    fun playTracks(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        scope.launch { library.remember(tracks.filter { it.source == SourceType.YOUTUBE }) }
        finishSession(skipped = _state.value.isPlaying)
        var q = QueueState().replace(tracks, startIndex.coerceIn(0, tracks.lastIndex))
        if (shuffle) q = q.shuffle(clock.now())
        _state.update { it.copy(queue = q, issue = null) }
        startCurrent(autoplay = true)
    }

    fun playNext(tracks: List<Track>) {
        val wasEmpty = _state.value.queue.items.isEmpty()
        _state.update { it.copy(queue = it.queue.playNext(tracks)) }
        if (wasEmpty) startCurrent(true) else onQueueEdited()
    }

    fun addToQueue(tracks: List<Track>) {
        val wasEmpty = _state.value.queue.items.isEmpty()
        _state.update { it.copy(queue = it.queue.append(tracks)) }
        if (wasEmpty) startCurrent(true) else onQueueEdited()
    }

    fun move(from: Int, to: Int) {
        _state.update { it.copy(queue = it.queue.move(from, to)) }
        onQueueEdited()
    }

    fun removeAt(index: Int) {
        val wasCurrent = index == _state.value.queue.currentIndex
        _state.update { it.copy(queue = it.queue.removeAt(index)) }
        if (wasCurrent) startCurrent(_state.value.isPlaying) else onQueueEdited()
    }

    fun skipTo(index: Int) {
        finishSession(skipped = true)
        _state.update { it.copy(queue = it.queue.skipTo(index)) }
        startCurrent(true)
    }

    fun togglePlay() = if (_state.value.isPlaying) pause() else play()

    fun play() {
        val s = _state.value
        if (s.current == null) return
        _state.update { it.copy(issue = null) }
        when (s.engine) {
            Engine.LOCAL -> controller?.let { c -> if (c.mediaItemCount == 0) startCurrent(true) else fadeTo(c, play = true) } ?: startCurrent(true)
            Engine.YOUTUBE -> if (youtube.currentVideoId == s.current?.playbackRef) youtube.play() else startCurrent(true)
            Engine.NONE -> startCurrent(true)
        }
    }

    fun pause() {
        when (_state.value.engine) {
            Engine.LOCAL -> controller?.let { fadeTo(it, play = false) }
            Engine.YOUTUBE -> youtube.pause()
            Engine.NONE -> Unit
        }
        _state.update { it.copy(isPlaying = false) }
    }

    fun next() {
        val s = _state.value
        finishSession(skipped = true)
        val nq = s.queue.next(repeatAll = s.repeat == RepeatMode.ALL)
        if (nq == s.queue) { pause(); return }
        _state.update { it.copy(queue = nq) }
        if (s.engine == Engine.LOCAL && nq.currentIndex in localRunStart until localRunStart + (controller?.mediaItemCount ?: 0) &&
            nq.current?.track?.source == SourceType.LOCAL) {
            controller?.seekTo(nq.currentIndex - localRunStart, 0)
            controller?.play()
        } else startCurrent(true)
    }

    fun previous() {
        val s = _state.value
        if (_progress.value.positionMs > 4_000 || !s.queue.hasPrevious) { seekTo(0); return }
        finishSession(skipped = true)
        _state.update { it.copy(queue = it.queue.previous()) }
        startCurrent(true)
    }

    fun seekTo(positionMs: Long) {
        when (_state.value.engine) {
            Engine.LOCAL -> controller?.seekTo(positionMs)
            Engine.YOUTUBE -> youtube.seekTo(positionMs / 1000f)
            Engine.NONE -> Unit
        }
        _progress.update { it.copy(positionMs = positionMs) }
    }

    fun toggleShuffle() {
        _state.update { s -> s.copy(queue = if (s.queue.shuffled) s.queue.unshuffle() else s.queue.shuffle(clock.now())) }
        onQueueEdited()
    }

    fun cycleRepeat() {
        _state.update { it.copy(repeat = RepeatMode.entries[(it.repeat.ordinal + 1) % RepeatMode.entries.size]) }
        controller?.repeatMode = if (_state.value.repeat == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun dismissIssue() = _state.update { it.copy(issue = null) }

    fun setSleepTimer(timer: SleepTimer?) {
        sleepJob?.cancel()
        _state.update { it.copy(sleep = timer) }
        if (timer is SleepTimer.Countdown) {
            sleepJob = scope.launch {
                while (isActive && clock.now() < timer.endsAt) delay(1_000)
                if (isActive) { pause(); _state.update { it.copy(sleep = null) } }
            }
        }
    }

    fun clearQueue() {
        finishSession(skipped = false)
        controller?.stop(); controller?.clearMediaItems()
        youtube.pause()
        _state.value = PlayerState(repeat = _state.value.repeat)
        _progress.value = Progress()
        persistQueue()
    }

    // endregion

    private fun startCurrent(autoplay: Boolean) {
        val s = _state.value
        val track = s.current ?: run { _state.update { it.copy(engine = Engine.NONE, isPlaying = false) }; return }
        beginSession(track)
        persistQueue()
        when (track.source) {
            SourceType.LOCAL -> {
                youtube.pause()
                _state.update { it.copy(engine = Engine.LOCAL, capabilities = PlaybackCapabilities.LocalMedia, isBuffering = true, isPlaying = autoplay) }
                withController { c -> loadLocalRun(c, autoplay) }
            }
            SourceType.YOUTUBE -> {
                controller?.pause()
                _state.update { it.copy(engine = Engine.YOUTUBE, capabilities = PlaybackCapabilities.YouTubeEmbed, isBuffering = true, isPlaying = autoplay) }
                _progress.value = Progress(0, track.durationMs ?: 0)
                youtube.load(track.playbackRef, 0f, autoplay)
            }
        }
        startProgressLoop()
    }

    private fun loadLocalRun(c: MediaController, autoplay: Boolean) {
        val q = _state.value.queue
        var start = q.currentIndex
        var end = q.currentIndex
        while (start > 0 && q.items[start - 1].track.source == SourceType.LOCAL) start--
        while (end < q.items.lastIndex && q.items[end + 1].track.source == SourceType.LOCAL) end++
        localRunStart = start
        val items = q.items.subList(start, end + 1).map { it.track.toMediaItem() }
        c.setMediaItems(items, q.currentIndex - start, 0L)
        c.repeatMode = if (_state.value.repeat == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        c.prepare()
        if (autoplay) fadeTo(c, play = true) else c.pause()
    }

    private fun onQueueEdited() {
        persistQueue()
        val s = _state.value
        if (s.engine == Engine.LOCAL && s.current?.source == SourceType.LOCAL) {
            // Re-sync ExoPlayer's window without interrupting the current track.
            val c = controller ?: return
            val pos = c.currentPosition
            val playing = c.isPlaying
            loadLocalRunPreservingPosition(c, pos, playing)
        }
    }

    private fun loadLocalRunPreservingPosition(c: MediaController, pos: Long, playing: Boolean) {
        val q = _state.value.queue
        var start = q.currentIndex
        var end = q.currentIndex
        while (start > 0 && q.items[start - 1].track.source == SourceType.LOCAL) start--
        while (end < q.items.lastIndex && q.items[end + 1].track.source == SourceType.LOCAL) end++
        localRunStart = start
        c.setMediaItems(q.items.subList(start, end + 1).map { it.track.toMediaItem() }, q.currentIndex - start, pos)
        c.prepare()
        if (playing) c.play()
    }

    private fun fadeTo(c: MediaController, play: Boolean) {
        val fade = settings.settings.value.fadeMs.toLong()
        if (fade <= 0) { c.volume = 1f; if (play) c.play() else c.pause(); return }
        scope.launch {
            val steps = 12
            if (play) {
                c.volume = 0f; c.play()
                for (i in 1..steps) { c.volume = i / steps.toFloat(); delay(fade / steps) }
            } else {
                val from = c.volume
                for (i in steps - 1 downTo 0) { c.volume = from * i / steps; delay(fade / steps) }
                c.pause(); c.volume = 1f
            }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let { block(it); return }
        pendingLocal = { controller?.let(block) }
        if (connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull() ?: run {
                Log.w("MediaController connection failed"); return@addListener
            }
            controller = c
            c.addListener(localListener)
            pendingLocal?.invoke()
            pendingLocal = null
        }, ContextCompat.getMainExecutor(context))
    }

    private val localListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (_state.value.engine == Engine.LOCAL) _state.update { it.copy(isPlaying = isPlaying || (controller?.playWhenReady == true && it.isBuffering)) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (_state.value.engine != Engine.LOCAL) return
            _state.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
            if (playbackState == Player.STATE_ENDED) onTrackEnded()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (_state.value.engine != Engine.LOCAL || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            val c = controller ?: return
            val newIndex = localRunStart + c.currentMediaItemIndex
            if (newIndex != _state.value.queue.currentIndex) {
                finishSession(skipped = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, completedNaturally = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                _state.update { it.copy(queue = it.queue.skipTo(newIndex)) }
                _state.value.current?.let(::beginSession)
                persistQueue()
                if (_state.value.sleep == SleepTimer.EndOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    c.pause(); _state.update { it.copy(sleep = null) }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val title = _state.value.current?.title ?: "This track"
            _state.update { it.copy(issue = PlaybackIssue.Unavailable(title), isPlaying = false) }
            scope.launch { delay(1_500); if (_state.value.issue is PlaybackIssue.Unavailable) next() }
        }
    }

    private fun onTrackEnded() {
        finishSession(skipped = false, completedNaturally = true)
        val s = _state.value
        when {
            s.sleep == SleepTimer.EndOfTrack -> { _state.update { it.copy(isPlaying = false, sleep = null) }; return }
            s.repeat == RepeatMode.ONE -> { seekTo(0); play(); return }
            !s.queue.hasNext && s.repeat != RepeatMode.ALL -> {
                _state.update { it.copy(isPlaying = false, sleep = if (it.sleep == SleepTimer.EndOfQueue) null else it.sleep) }
                return
            }
        }
        _state.update { it.copy(queue = it.queue.next(repeatAll = it.repeat == RepeatMode.ALL)) }
        startCurrent(true)
    }

    // region YouTube events
    override fun onYtState(state: PlayerConstants.PlayerState) {
        if (_state.value.engine != Engine.YOUTUBE) return
        when (state) {
            PlayerConstants.PlayerState.PLAYING -> _state.update { it.copy(isPlaying = true, isBuffering = false, issue = null) }
            PlayerConstants.PlayerState.PAUSED -> _state.update { it.copy(isPlaying = false, isBuffering = false) }
            PlayerConstants.PlayerState.BUFFERING -> _state.update { it.copy(isBuffering = true) }
            PlayerConstants.PlayerState.ENDED -> onTrackEnded()
            PlayerConstants.PlayerState.VIDEO_CUED -> _state.update { it.copy(isBuffering = false, isPlaying = false) }
            else -> Unit
        }
    }

    override fun onYtSecond(second: Float) {
        if (_state.value.engine == Engine.YOUTUBE) _progress.update { it.copy(positionMs = (second * 1000).toLong()) }
    }

    override fun onYtDuration(duration: Float) {
        if (_state.value.engine == Engine.YOUTUBE && duration > 0) _progress.update { it.copy(durationMs = (duration * 1000).toLong()) }
    }

    override fun onYtError(error: PlayerConstants.PlayerError) {
        val title = _state.value.current?.title ?: "This video"
        _state.update { it.copy(issue = PlaybackIssue.Unavailable(title), isPlaying = false, isBuffering = false) }
        scope.launch { delay(1_800); if (_state.value.issue is PlaybackIssue.Unavailable) next() }
    }
    // endregion

    private fun startProgressLoop() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                val now = clock.now()
                val s = _state.value
                if (s.isPlaying && !s.isBuffering && lastTickAt > 0) sessionListenedMs += (now - lastTickAt).coerceIn(0, 2_000)
                lastTickAt = now
                if (s.engine == Engine.LOCAL) {
                    controller?.let { c -> _progress.value = Progress(c.currentPosition.coerceAtLeast(0), c.duration.takeIf { it > 0 } ?: (s.current?.durationMs ?: 0)) }
                }
                delay(if (s.isPlaying) 250 else 1_000)
            }
        }
    }

    private fun beginSession(track: Track) {
        sessionTrack = track
        sessionStartedAt = clock.now()
        sessionListenedMs = 0
        lastTickAt = 0
    }

    /** Converts the finished listen into a local PlayEvent. Under 5 s is noise and ignored. */
    private fun finishSession(skipped: Boolean, completedNaturally: Boolean = false) {
        val t = sessionTrack ?: return
        sessionTrack = null
        val listened = sessionListenedMs
        if (listened < 5_000) return
        val duration = t.durationMs ?: _progress.value.durationMs.takeIf { it > 0 }
        val completed = completedNaturally || (duration != null && listened >= duration * 0.8)
        val event = PlayEvent(t.id, t.artistKey, sessionStartedAt, listened, duration, completed, skipped = skipped && !completed && listened < 30_000 + (duration ?: 0) / 3)
        scope.launch { runCatching { library.recordPlay(event, t.source.name) } }
    }

    private fun persistQueue() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(500)
            val q = _state.value.queue
            runCatching {
                prefs.edit()
                    .putString("queue", json.encodeToString(ListSerializer(Track.serializer()), q.items.map { it.track }.take(300)))
                    .putInt("index", q.currentIndex)
                    .apply()
            }
        }
    }

    private fun restoreQueue() {
        runCatching {
            val raw = prefs.getString("queue", null) ?: return
            val tracks = json.decodeFromString(ListSerializer(Track.serializer()), raw)
            if (tracks.isEmpty()) return
            val q = QueueState().replace(tracks, prefs.getInt("index", 0))
            val engine = if (q.current?.track?.source == SourceType.YOUTUBE) Engine.YOUTUBE else Engine.LOCAL
            _state.value = PlayerState(queue = q, engine = Engine.NONE, capabilities = if (engine == Engine.YOUTUBE) PlaybackCapabilities.YouTubeEmbed else PlaybackCapabilities.LocalMedia)
            _progress.value = Progress(0, q.current?.track?.durationMs ?: 0)
        }
    }

    private fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.value)
        .setUri(Uri.parse(playbackRef))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artworkUrl?.let(Uri::parse))
                .build(),
        )
        .build()
}
