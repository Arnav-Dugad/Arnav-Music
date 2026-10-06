package com.arnav.music.core.playback

import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener

/**
 * Bridge to the official YouTube IFrame player (embedded, visible, ads and attribution intact).
 * The player view itself lives in the UI; this class only relays commands and events.
 */
class YouTubeEngine {
    interface Events {
        fun onYtState(state: PlayerConstants.PlayerState)
        fun onYtSecond(second: Float)
        fun onYtDuration(duration: Float)
        fun onYtError(error: PlayerConstants.PlayerError)
    }

    private var player: YouTubePlayer? = null
    private var pending: Pair<String, Float>? = null
    private var pendingPlay = true
    var events: Events? = null
    var currentVideoId: String? = null
        private set

    val isAttached: Boolean get() = player != null

    val listener = object : AbstractYouTubePlayerListener() {
        override fun onReady(youTubePlayer: YouTubePlayer) {
            player = youTubePlayer
            pending?.let { (id, start) -> if (pendingPlay) youTubePlayer.loadVideo(id, start) else youTubePlayer.cueVideo(id, start) }
            pending = null
        }
        override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) { events?.onYtState(state) }
        override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) { events?.onYtSecond(second) }
        override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) { events?.onYtDuration(duration) }
        override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) { events?.onYtError(error) }
    }

    fun load(videoId: String, startSeconds: Float = 0f, autoplay: Boolean = true) {
        currentVideoId = videoId
        val p = player
        if (p == null) { pending = videoId to startSeconds; pendingPlay = autoplay; return }
        if (autoplay) p.loadVideo(videoId, startSeconds) else p.cueVideo(videoId, startSeconds)
    }

    fun play() { player?.play() }
    fun pause() { player?.pause() }
    fun seekTo(seconds: Float) { player?.seekTo(seconds) }

    /** Called when the hosting view is released (e.g. activity destroyed). */
    fun detach() {
        player = null
        currentVideoId?.let { pending = it to 0f; pendingPlay = false }
    }
}
