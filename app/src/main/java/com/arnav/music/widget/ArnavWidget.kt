package com.arnav.music.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.arnav.music.MainActivity
import com.arnav.music.R
import com.arnav.music.core.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/** Lightweight snapshot the widget renders (written by the app; no player is created to draw it). */
internal data class WidgetSnapshot(
    val title: String?, val artist: String?, val artworkUrl: String?, val playing: Boolean, val youtube: Boolean, val hasNext: Boolean,
) {
    companion object {
        private const val PREFS = "widget_snapshot"
        fun read(context: Context): WidgetSnapshot {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return WidgetSnapshot(p.getString("t", null), p.getString("a", null), p.getString("art", null), p.getBoolean("p", false), p.getBoolean("yt", false), p.getBoolean("n", false))
        }
        fun write(context: Context, s: WidgetSnapshot) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("t", s.title).putString("a", s.artist).putString("art", s.artworkUrl)
                .putBoolean("p", s.playing).putBoolean("yt", s.youtube).putBoolean("n", s.hasNext).apply()
        }
    }
}

class ArnavWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(180.dp, 64.dp), DpSize(260.dp, 110.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = WidgetSnapshot.read(context)
        val art = snap.artworkUrl?.let { loadBitmap(context, it) }
        provideContent { Content(context, snap, art) }
    }

    private suspend fun loadBitmap(context: Context, url: String): Bitmap? = runCatching {
        val r = context.imageLoader.execute(ImageRequest.Builder(context).data(url).size(256).allowHardware(false).build())
        (r as? SuccessResult)?.image?.toBitmap()
    }.getOrNull()

    @Composable
    private fun Content(context: Context, s: WidgetSnapshot, art: Bitmap?) {
        val tall = LocalSize.current.height >= 100.dp
        val white = ColorProvider(Color.White)
        val muted = ColorProvider(Color(0xB3FFFFFF))
        val open = actionStartActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true))
        Column(
            GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(Color(0xFF15121F)).padding(12.dp),
        ) {
            Row(GlanceModifier.fillMaxWidth().clickable(open), verticalAlignment = Alignment.CenterVertically) {
                if (art != null) {
                    Image(ImageProvider(art), contentDescription = null, contentScale = ContentScale.Crop, modifier = GlanceModifier.size(if (tall) 64.dp else 44.dp).cornerRadius(14.dp))
                } else {
                    Box(GlanceModifier.size(if (tall) 64.dp else 44.dp).cornerRadius(14.dp).background(Color(0xFF2A1F5C)), contentAlignment = Alignment.Center) {
                        Image(ImageProvider(R.drawable.ic_stat_arnav), contentDescription = null, modifier = GlanceModifier.size(22.dp))
                    }
                }
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(s.title ?: "Arnav Music", style = TextStyle(color = white, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    Text(s.artist ?: "Tap to start listening", style = TextStyle(color = muted, fontSize = 12.sp), maxLines = 1)
                }
            }
            if (tall) {
                Spacer(GlanceModifier.height(10.dp))
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Button(R.drawable.ic_w_prev, "Previous", actionRunCallback<PrevAction>())
                    // YouTube can't play while the app is hidden, so play opens the app for YouTube tracks.
                    Button(if (s.playing) R.drawable.ic_w_pause else R.drawable.ic_w_play, if (s.playing) "Pause" else "Play",
                        if (s.youtube && !s.playing) open else actionRunCallback<PlayPauseAction>())
                    Button(R.drawable.ic_w_next, "Next", actionRunCallback<NextAction>())
                    Spacer(GlanceModifier.defaultWeight())
                    Button(R.drawable.ic_w_ai, "Ask Arnav AI", actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse("arnavmusic://ai"), context, MainActivity::class.java)))
                    Button(R.drawable.ic_w_moment, "Moments", actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse("arnavmusic://moment/night_drive"), context, MainActivity::class.java)))
                }
            }
        }
    }

    @Composable
    private fun Button(icon: Int, label: String, action: androidx.glance.action.Action) {
        Box(GlanceModifier.size(40.dp).cornerRadius(20.dp).clickable(action), contentAlignment = Alignment.Center) {
            Image(ImageProvider(icon), contentDescription = label, modifier = GlanceModifier.size(22.dp))
        }
    }

    companion object {
        /** Called by the app when playback changes. Cheap: writes a snapshot and re-renders. */
        suspend fun refresh(context: Context, title: String?, artist: String?, artworkUrl: String?, playing: Boolean, youtube: Boolean, hasNext: Boolean) {
            WidgetSnapshot.write(context, WidgetSnapshot(title, artist, artworkUrl, playing, youtube, hasNext))
            runCatching { ArnavWidget().updateAll(context) }
        }
    }
}

class ArnavWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ArnavWidget()
}

private suspend fun withPlayer(block: (PlaybackController) -> Unit) = withContext(Dispatchers.Main) {
    runCatching { block(GlobalContext.get().get()) }
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withPlayer { p ->
            // Never start YouTube playback from the background.
            if (p.state.value.current?.source == com.arnav.music.domain.model.SourceType.YOUTUBE && !p.state.value.isPlaying) return@withPlayer
            p.togglePlay()
        }
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withPlayer { p -> if (p.state.value.engine != com.arnav.music.core.playback.Engine.YOUTUBE) p.next() }
    }
}

class PrevAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withPlayer { p -> if (p.state.value.engine != com.arnav.music.core.playback.Engine.YOUTUBE) p.previous() }
    }
}
