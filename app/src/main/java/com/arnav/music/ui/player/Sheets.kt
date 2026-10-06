package com.arnav.music.ui.player

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arnav.music.core.playback.SleepTimer
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.model.Playlist
import com.arnav.music.domain.model.PlaylistKind
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.ui.components.Artwork
import com.arnav.music.ui.components.EmptyState
import com.arnav.music.ui.components.Pill
import com.arnav.music.ui.components.PrimaryButton
import com.arnav.music.ui.components.SourceBadge
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space

/** Which sheet is open. Exactly one at a time, owned by the app root. */
sealed interface SheetRequest {
    data class TrackActions(val track: Track) : SheetRequest
    data class AddToPlaylist(val tracks: List<Track>) : SheetRequest
    data class Lyrics(val track: Track) : SheetRequest
    data object Sleep : SheetRequest
    data class CreatePlaylist(val tracks: List<Track>) : SheetRequest
}

@Composable
fun ArnavSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = ArnavTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceRaised,
        contentColor = c.content,
        scrimColor = c.scrim,
        shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Space.l)) { content() }
    }
}

@Composable
fun TrackActionsSheet(
    track: Track,
    liked: Boolean,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onQueue: () -> Unit,
    onLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onArtist: () -> Unit,
    onShare: () -> Unit,
    onLyrics: () -> Unit,
) {
    val c = ArnavTheme.colors
    val context = LocalContext.current
    var info by remember { mutableStateOf(false) }
    ArnavSheet(onDismiss) {
        Row(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.artworkUrl, track.id.value, Modifier.size(56.dp), RoundedCornerShape(Radius.s))
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = ArnavTheme.type.title, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = ArnavTheme.type.bodySmall, color = c.contentMuted, maxLines = 1)
            }
            SourceBadge(track.source == SourceType.YOUTUBE)
        }
        Spacer(Modifier.height(Space.s))
        fun act(f: () -> Unit) = { f(); onDismiss() }
        ActionRow(Icons.Rounded.PlaylistPlay, "Play next", act(onPlayNext))
        ActionRow(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue", act(onQueue))
        ActionRow(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Remove from liked" else "Like", act(onLike))
        ActionRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", onAddToPlaylist)
        ActionRow(Icons.Rounded.Person, "Go to ${track.artist}", act(onArtist))
        ActionRow(Icons.Rounded.Lyrics, "Lyrics", onLyrics)
        ActionRow(Icons.Rounded.Share, "Share", act(onShare))
        if (track.source == SourceType.YOUTUBE) {
            ActionRow(Icons.Rounded.OpenInNew, "Open in YouTube Music", act {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=${track.playbackRef}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
        }
        ActionRow(Icons.Rounded.Info, if (info) "Hide details" else "Song details", { info = !info })
        if (info) {
            Column(Modifier.padding(horizontal = Space.gutter + Space.xl, vertical = Space.s)) {
                listOfNotNull(
                    "Source" to if (track.source == SourceType.YOUTUBE) "YouTube (embedded player)" else "This device",
                    track.album?.let { "Album" to it },
                    track.durationMs?.let { "Length" to Formatters.duration(it) },
                    track.year?.let { "Year" to it.toString() },
                    track.genres.takeIf { it.isNotEmpty() }?.let { "Style hints" to it.joinToString() },
                    track.energy?.let { "Estimated energy" to "${(it * 100).toInt()}% (from public metadata)" },
                ).forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(k, style = ArnavTheme.type.bodySmall, color = c.contentSubtle, modifier = Modifier.width(120.dp))
                        Text(v, style = ArnavTheme.type.bodySmall, color = c.content)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = ArnavTheme.colors
    val haptics = ArnavTheme.haptics
    Row(
        Modifier.fillMaxWidth().height(52.dp).clickable(role = Role.Button) { haptics.select(); onClick() }.padding(horizontal = Space.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.contentMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(Space.l))
        Text(label, style = ArnavTheme.type.body, color = c.content)
    }
}

@Composable
fun PlaylistPickerSheet(playlists: List<Playlist>, onDismiss: () -> Unit, onPick: (String) -> Unit, onCreate: () -> Unit) {
    val c = ArnavTheme.colors
    ArnavSheet(onDismiss) {
        Text("Add to playlist", style = ArnavTheme.type.title, color = c.content, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s))
        ActionRow(Icons.Rounded.Add, "New playlist", onCreate)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            playlists.filter { it.kind == PlaylistKind.ARNAV }.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(p.id); onDismiss() }.padding(horizontal = Space.gutter, vertical = Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(p.artworkUrl, p.id, Modifier.size(44.dp), RoundedCornerShape(Radius.s), decodeSize = 140)
                    Spacer(Modifier.width(Space.m))
                    Column {
                        Text(p.name, style = ArnavTheme.type.titleSmall, color = c.content)
                        Text("${p.trackCount} tracks", style = ArnavTheme.type.caption, color = c.contentMuted)
                    }
                }
            }
        }
    }
}

@Composable
fun CreatePlaylistSheet(count: Int, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val c = ArnavTheme.colors
    var name by remember { mutableStateOf("") }
    ArnavSheet(onDismiss) {
        Column(Modifier.padding(horizontal = Space.gutter)) {
            Text("New playlist", style = ArnavTheme.type.title, color = c.content)
            if (count > 0) Text("With $count ${if (count == 1) "track" else "tracks"}", style = ArnavTheme.type.bodySmall, color = c.contentMuted)
            Spacer(Modifier.height(Space.l))
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(100) }, singleLine = true,
                placeholder = { Text("Name it something you'll remember") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radius.m),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) { onCreate(name); onDismiss() } }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.outline, cursorColor = c.accent),
            )
            Spacer(Modifier.height(Space.l))
            PrimaryButton("Create", { onCreate(name); onDismiss() }, Modifier.fillMaxWidth(), enabled = name.isNotBlank())
        }
    }
}

@Composable
fun SleepTimerSheet(current: SleepTimer?, onDismiss: () -> Unit, onSet: (SleepTimer?) -> Unit) {
    val c = ArnavTheme.colors
    ArnavSheet(onDismiss) {
        Column(Modifier.padding(horizontal = Space.gutter)) {
            Text("Sleep timer", style = ArnavTheme.type.title, color = c.content)
            Text("Music fades out gently when the timer ends.", style = ArnavTheme.type.bodySmall, color = c.contentMuted)
            Spacer(Modifier.height(Space.l))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                listOf(5, 10, 15, 30, 45, 60).forEach { m ->
                    Pill("$m min", false, {
                        val ms = m * 60_000L
                        onSet(SleepTimer.Countdown(System.currentTimeMillis() + ms, ms)); onDismiss()
                    })
                }
                Pill("End of track", current == SleepTimer.EndOfTrack, { onSet(SleepTimer.EndOfTrack); onDismiss() })
                Pill("End of queue", current == SleepTimer.EndOfQueue, { onSet(SleepTimer.EndOfQueue); onDismiss() })
            }
            if (current != null) {
                Spacer(Modifier.height(Space.l))
                Text("Turn off timer", style = ArnavTheme.type.label, color = c.danger,
                    modifier = Modifier.clip(RoundedCornerShape(Radius.s)).clickable { onSet(null); onDismiss() }.padding(Space.s))
            }
        }
    }
}

/**
 * Lyrics are shown only from sources licensed for display. No scraping. Until a licensed
 * provider is configured this is an honest, designed empty state.
 */
@Composable
fun LyricsSheet(track: Track, onDismiss: () -> Unit) {
    ArnavSheet(onDismiss) {
        EmptyState(
            Icons.Rounded.Lyrics,
            "Lyrics aren't available yet",
            "Arnav Music only shows lyrics from licensed providers. “${track.title.take(40)}” has no licensed lyrics source connected.",
        )
        Box(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
            Text(
                "We never copy lyrics from websites — it isn't fair to songwriters.",
                style = ArnavTheme.type.caption, color = ArnavTheme.colors.contentSubtle, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Suppress("unused") private val unusedBg = Modifier.background(androidx.compose.ui.graphics.Color.Transparent)
