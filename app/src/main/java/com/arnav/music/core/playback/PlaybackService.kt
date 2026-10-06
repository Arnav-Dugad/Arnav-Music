package com.arnav.music.core.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.arnav.music.MainActivity
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Native background playback for user-owned local audio: MediaSession, media notification,
 * lock-screen, Bluetooth and headset controls, audio focus, becoming-noisy handling, gapless.
 * YouTube content is never routed through here.
 */
@OptIn(UnstableApi::class) // Media button preferences (Like button) are marked unstable in Media3.
class PlaybackService : MediaSessionService() {
    private val settings: SettingsRepository by inject()
    private val library: LibraryRepository by inject()
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Media id (= TrackId value) of the current item, for the notification's Like button. */
    private val currentMediaId = MutableStateFlow<String?>(null)
    /** null when nothing is loaded (no Like button), otherwise whether the current item is liked. */
    private var liked: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(settings.settings.value.pauseOnDisconnect)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) { _audioSessionId.value = audioSessionId }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { currentMediaId.value = player.currentMediaItem?.mediaId?.ifEmpty { null } }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) { currentMediaId.value = player.currentMediaItem?.mediaId?.ifEmpty { null } }
        })
        _audioSessionId.value = player.audioSessionId

        scope.launch {
            settings.settings.map { Triple(it.skipSilence, it.playbackSpeed, it.pauseOnDisconnect) }.distinctUntilChanged().collect { (skip, speed, noisy) ->
                player.skipSilenceEnabled = skip
                player.setPlaybackSpeed(speed.coerceIn(0.5f, 2f))
                player.setHandleAudioBecomingNoisy(noisy)
            }
        }

        val activityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(activityIntent)
            .setCallback(sessionCallback)
            .build()

        // Keep the heart in the notification / lock screen in sync with the current item and likes.
        scope.launch {
            combine(currentMediaId, library.likedIds) { id, likedIds -> id?.let { TrackId(it) in likedIds } }
                .distinctUntilChanged()
                .collect { state ->
                    liked = state
                    session?.setMediaButtonPreferences(likeButtons(state))
                }
        }
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(LIKE_COMMAND).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(likeButtons(liked))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != ACTION_TOGGLE_LIKE) return super.onCustomCommand(session, controller, customCommand, args)
            toggleLikeForCurrent(session)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /** Likes/unlikes the current item; the [LibraryRepository.likedIds] observer then updates the button. */
    private fun toggleLikeForCurrent(session: MediaSession) {
        val item = session.player.currentMediaItem ?: return
        val id = item.mediaId.ifEmpty { return }
        scope.launch {
            val track = withContext(Dispatchers.IO) {
                runCatching { library.tracks(listOf(TrackId(id))).firstOrNull() }.getOrNull()
            } ?: item.toTrack(id) ?: return@launch
            runCatching { library.toggleLike(track) }
        }
    }

    /** Fallback when the library can't resolve the id: rebuild the Track from the item's metadata. */
    private fun MediaItem.toTrack(id: String): Track? {
        val uri = localConfiguration?.uri?.toString() ?: return null
        return Track(
            id = TrackId(id),
            title = mediaMetadata.title?.toString() ?: return null,
            artist = mediaMetadata.artist?.toString().orEmpty(),
            album = mediaMetadata.albumTitle?.toString(),
            artworkUrl = mediaMetadata.artworkUri?.toString(),
            playbackRef = uri,
        )
    }

    private fun likeButtons(state: Boolean?): List<CommandButton> {
        if (state == null) return emptyList()
        return listOf(
            CommandButton.Builder(if (state) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(if (state) "Remove from liked songs" else "Like")
                .setSessionCommand(LIKE_COMMAND)
                .build(),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_TOGGLE_LIKE = "com.arnav.music.TOGGLE_LIKE"
        private val LIKE_COMMAND = SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY)

        private val _audioSessionId = MutableStateFlow(C.AUDIO_SESSION_ID_UNSET)
        val audioSessionId: StateFlow<Int> = _audioSessionId
    }
}
