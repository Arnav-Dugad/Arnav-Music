package com.arnav.music.feature.collection

import com.arnav.music.ui.sharedArt
import com.arnav.music.ui.ArtKeys
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import com.arnav.music.core.db.PendingMatchEntity
import com.arnav.music.feature.library.rememberYouTubeAuthorizer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.provider.MusicError
import com.arnav.music.ui.CollectionKind
import com.arnav.music.ui.LocalAppViewModel
import com.arnav.music.ui.LocalChromePadding
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.EmptyState
import com.arnav.music.ui.components.Mosaic
import com.arnav.music.ui.components.Pill
import com.arnav.music.ui.components.PrimaryButton
import com.arnav.music.ui.components.SecondaryButton
import com.arnav.music.ui.components.SourceBadge
import com.arnav.music.ui.components.TrackRow
import com.arnav.music.ui.components.TrackRowSkeleton
import com.arnav.music.ui.artwork.rememberArtworkPalette
import com.arnav.music.ui.player.SheetRequest
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import com.arnav.music.domain.color.SurfaceMode
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun CollectionScreen(kind: CollectionKind, id: String, vm: CollectionViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val app = LocalAppViewModel.current
    val nav = LocalNavigator.current
    val c = ArnavTheme.colors
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRefresh by remember { mutableStateOf(false) }
    val matching by vm.matching.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val authorizeYouTube = rememberYouTubeAuthorizer(
        onToken = { token -> vm.refreshFromYouTube(token) { msg -> app.message(msg) } },
        onError = { msg -> app.message(msg) },
    )
    LaunchedEffect(kind, id) { vm.load(kind, id) }
    val visible = remember(ui, filter, sort) { vm.visibleTracks(ui, filter, sort) }
    // "Shuffle liked" deep action from the command palette.
    LaunchedEffect(ui.tracks.isNotEmpty()) { if (id == "shuffle" && ui.tracks.isNotEmpty()) app.play(ui.tracks, 0, shuffle = true) }

    CollectionScaffold(
        sharedKey = ArtKeys.collection(kind, id),
        title = ui.title, subtitle = ui.subtitle, kindLabel = ui.kindLabel, description = ui.description, tracks = visible, allTracks = ui.tracks,
        loading = ui.loading, youtube = ui.youtube,
        actions = {
            if (ui.youtubeImport) {
                if (refreshing) {
                    Box(Modifier.size(Space.touch), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                    }
                } else {
                    ArnavIconButton(Icons.Rounded.Sync, "Refresh from YouTube", { confirmRefresh = true }, tint = c.contentMuted)
                }
            }
            if (ui.editable) {
                ArnavIconButton(Icons.Rounded.PushPin, if (ui.pinned) "Unpin" else "Pin to top", vm::togglePin, tint = if (ui.pinned) c.accent else c.contentMuted)
                ArnavIconButton(Icons.Rounded.Edit, "Rename", { editing = true }, tint = c.contentMuted)
                ArnavIconButton(Icons.Rounded.Delete, "Delete playlist", { confirmDelete = true }, tint = c.contentMuted)
            }
            if (kind == CollectionKind.YOUTUBE_PLAYLIST) {
                ArnavIconButton(if (ui.savedToLibrary) Icons.Rounded.BookmarkAdded else Icons.Rounded.BookmarkAdd, if (ui.savedToLibrary) "Saved to library" else "Save to library", vm::saveYouTube, tint = if (ui.savedToLibrary) c.accent else c.contentMuted)
                ArnavIconButton(Icons.Rounded.ContentCopy, "Copy into an Arnav playlist", { vm.duplicateAsArnav(); app.message("Copied into an Arnav playlist") }, tint = c.contentMuted)
            }
        },
        filter = filter, onFilter = { vm.filter.value = it }, sort = sort, onSort = { vm.sort.value = it },
        error = ui.error,
        onRemove = if (ui.editable) { t -> vm.removeTrack(t) } else null,
        history = if (kind == CollectionKind.HISTORY) ui.historyDays else null,
        pending = ui.pending, matchingIds = matching,
        onPendingTap = { row -> vm.matchPending(row) { msg -> app.message(msg) } },
        onPendingRemove = { row -> vm.removePending(row) },
        emptyTitle = when (kind) {
            CollectionKind.LIKED -> "Your favorites will appear here"
            CollectionKind.LOCAL -> "Bring your own library"
            CollectionKind.HISTORY -> "Your next obsession starts here"
            CollectionKind.SMART -> "Not enough listening yet"
            else -> "This playlist is empty"
        },
        emptyBody = when (kind) {
            CollectionKind.LIKED -> "Tap the heart on anything you love."
            CollectionKind.LOCAL -> "Allow access in Library → On device to play files from this phone."
            CollectionKind.HISTORY -> "Everything you play through Arnav Music shows up here — only on this device."
            CollectionKind.SMART -> "Smart playlists fill themselves as you listen. Check back after a few sessions."
            else -> "Add songs from any track's menu, or swipe right on a song to queue it."
        },
    )

    if (editing) {
        var name by remember { mutableStateOf(ui.title) }
        var desc by remember { mutableStateOf(ui.description) }
        AlertDialog(
            onDismissRequest = { editing = false }, containerColor = c.surfaceRaised,
            title = { Text("Edit playlist", style = ArnavTheme.type.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    OutlinedTextField(name, { name = it.take(100) }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(desc, { desc = it.take(500) }, label = { Text("Description") })
                }
            },
            confirmButton = { TextButton({ vm.rename(name, desc); editing = false }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton({ editing = false }) { Text("Cancel") } },
        )
    }
    if (confirmRefresh) {
        AlertDialog(
            onDismissRequest = { confirmRefresh = false }, containerColor = c.surfaceRaised,
            title = { Text("Refresh from YouTube?") },
            text = { Text("Refreshing replaces songs with the current YouTube version of this playlist. Songs you added or removed here in Arnav will be replaced too.") },
            confirmButton = { TextButton({ confirmRefresh = false; authorizeYouTube() }) { Text("Refresh") } },
            dismissButton = { TextButton({ confirmRefresh = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, containerColor = c.surfaceRaised,
            title = { Text("Delete “${ui.title}”?") },
            text = { Text("This removes the playlist from this device and your synced account. Songs stay in your library.") },
            confirmButton = { TextButton({ vm.delete(); confirmDelete = false; nav.back() }) { Text("Delete", color = c.danger) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
fun ArtistScreen(name: String, vm: CollectionViewModel = koinViewModel()) {
    val ui by vm.artist.collectAsStateWithLifecycle()
    LaunchedEffect(name) { vm.loadArtist(name) }
    CollectionScaffold(
        title = ui.title.ifBlank { name }, subtitle = ui.subtitle, kindLabel = "Artist", description = "", tracks = ui.tracks, allTracks = ui.tracks,
        loading = ui.loading && ui.tracks.isEmpty(), youtube = ui.youtube, actions = {}, filter = "", onFilter = null, sort = SmartSort.DEFAULT, onSort = null,
        error = ui.error, onRemove = null, history = null, round = true,
        emptyTitle = "Nothing from $name yet", emptyBody = "Search YouTube for $name, or add their music to your device.",
    )
}

@Composable
private fun CollectionScaffold(
    title: String, subtitle: String, kindLabel: String, description: String,
    tracks: List<Track>, allTracks: List<Track>, loading: Boolean, youtube: Boolean,
    actions: @Composable () -> Unit,
    filter: String, onFilter: ((String) -> Unit)?, sort: SmartSort, onSort: ((SmartSort) -> Unit)?,
    error: MusicError?, onRemove: ((Track) -> Unit)?, history: List<Pair<String, List<Track>>>?,
    emptyTitle: String, emptyBody: String, round: Boolean = false,
    pending: List<PendingMatchEntity> = emptyList(), matchingIds: Set<Long> = emptySet(),
    onPendingTap: ((PendingMatchEntity) -> Unit)? = null, onPendingRemove: ((PendingMatchEntity) -> Unit)? = null,
    sharedKey: String? = null,
) {
    val app = LocalAppViewModel.current
    val nav = LocalNavigator.current
    val c = ArnavTheme.colors
    val liked by app.liked.collectAsStateWithLifecycle()
    val player by app.playerState.collectAsStateWithLifecycle()
    val chrome = LocalChromePadding.current
    val listState = rememberLazyListState()
    val art = allTracks.mapNotNull { it.artworkUrl }.distinct().take(4)
    val palette = rememberArtworkPalette(art.firstOrNull(), if (c.isOled) SurfaceMode.OLED else if (c.isDark) SurfaceMode.DARK else SurfaceMode.LIGHT)
    val playingId = player.current?.id

    Box(Modifier.fillMaxSize()) {
        // Dynamic hero background tinted by the collection's artwork.
        Box(
            Modifier.fillMaxWidth().height(420.dp)
                .graphicsLayer { translationY = if (listState.firstVisibleItemIndex == 0) -listState.firstVisibleItemScrollOffset * 0.4f else -420.dp.toPx() }
                .background(Brush.verticalGradient(listOf(Color(palette.backdrop), c.background))),
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = chrome.calculateBottomPadding() + Space.xl)) {
            item(key = "top") {
                Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    ArnavIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", nav::back)
                    Spacer(Modifier.weight(1f))
                    actions()
                }
            }
            item(key = "hero") {
                Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
                    Mosaic(art, title, Modifier.sharedArt(sharedKey).size(220.dp).graphicsLayer { shadowElevation = 24.dp.toPx(); shape = if (round) CircleShape else RoundedCornerShape(Radius.heroArtwork); clip = true },
                        shape = if (round) CircleShape else RoundedCornerShape(Radius.heroArtwork))
                    Spacer(Modifier.height(Space.xl))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(kindLabel.uppercase(), style = ArnavTheme.type.overline, color = c.contentMuted)
                        if (youtube) { Spacer(Modifier.width(Space.s)); SourceBadge(youtube = true) }
                    }
                    Spacer(Modifier.height(Space.xs))
                    Text(title, style = ArnavTheme.type.display, color = c.content, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (description.isNotBlank()) {
                        Spacer(Modifier.height(Space.xs))
                        Text(description, style = ArnavTheme.type.bodySmall, color = c.contentMuted, maxLines = 3, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    Spacer(Modifier.height(Space.xs))
                    Text(subtitle, style = ArnavTheme.type.caption, color = c.contentSubtle)
                    Spacer(Modifier.height(Space.l))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        PrimaryButton("Play", { app.play(tracks, 0) }, enabled = tracks.isNotEmpty(), icon = Icons.Rounded.PlayArrow)
                        SecondaryButton("Shuffle", { app.play(tracks, 0, shuffle = true) }, icon = Icons.Rounded.Shuffle, enabled = tracks.size > 1)
                    }
                }
            }
            if (onFilter != null && allTracks.size > 8) item(key = "filter") {
                Column(Modifier.padding(top = Space.l)) {
                    Row(
                        Modifier.padding(horizontal = Space.gutter).fillMaxWidth().height(44.dp).glass(GlassMaterial.Thin, RoundedCornerShape(Radius.m)).padding(horizontal = Space.l),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.FilterList, null, tint = c.contentSubtle, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Space.m))
                        BasicTextField(filter, onFilter, singleLine = true, textStyle = ArnavTheme.type.body.copy(color = c.content), cursorBrush = SolidColor(c.accent), modifier = Modifier.weight(1f),
                            decorationBox = { inner -> if (filter.isEmpty()) Text("Find in this list", style = ArnavTheme.type.body, color = c.contentSubtle); inner() })
                    }
                    if (onSort != null) LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter, vertical = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        items(SmartSort.entries) { s -> Pill(s.label, s == sort, { onSort(s) }) }
                    }
                }
            }
            when {
                loading -> items(8) { TrackRowSkeleton() }
                error != null && allTracks.isEmpty() -> item { ErrorBlock(error) }
                allTracks.isEmpty() && pending.isEmpty() -> item { EmptyState(Icons.Rounded.MusicOff, emptyTitle, emptyBody) }
                history != null && filter.isBlank() -> historyItems(history, playingId, liked)
                else -> itemsIndexed(tracks, key = { _, t -> t.id.value }) { i, t ->
                    TrackRow(
                        t, { app.play(tracks, i) }, Modifier.animateItem(), playing = t.id == playingId, liked = t.id in liked,
                        onQueue = { app.addToQueue(t) },
                        onLike = if (onRemove != null) ({ onRemove(t) }) else ({ app.toggleLike(t) }),
                        leftSwipeRemoves = onRemove != null,
                        onMore = { nav.openSheet(SheetRequest.TrackActions(t)) },
                    )
                }
            }
            if (!loading && history == null && pending.isNotEmpty()) {
                val shown = if (filter.isBlank()) pending else pending.filter { it.title.contains(filter, true) || it.artist.contains(filter, true) }
                val waiting = pending.count { !it.failed }
                if (shown.isNotEmpty()) item(key = "pending_header") {
                    Column(Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.gutter, top = Space.xl, bottom = Space.s)) {
                        Text(
                            if (waiting > 0) "$waiting waiting to match" else "Not matched",
                            style = ArnavTheme.type.label, color = c.contentMuted,
                        )
                        Text(
                            if (waiting > 0) "Matched to YouTube a few at a time, within the free daily search limit. Tap a song to match it now."
                            else "No close enough upload was found on YouTube for these songs.",
                            style = ArnavTheme.type.caption, color = c.contentSubtle,
                        )
                    }
                }
                items(shown, key = { "pending_${it.id}" }) { row ->
                    PendingRow(row, matching = row.id in matchingIds, onTap = onPendingTap?.let { f -> { f(row) } }, onRemove = onPendingRemove?.let { f -> { f(row) } })
                }
            }
            if (youtube) item(key = "attrib") {
                Text("Music and metadata from YouTube. Plays in the official YouTube player.", style = ArnavTheme.type.caption, color = c.contentSubtle,
                    modifier = Modifier.fillMaxWidth().padding(Space.gutter), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

private fun LazyListScope.historyItems(days: List<Pair<String, List<Track>>>, playingId: com.arnav.music.domain.model.TrackId?, liked: Set<com.arnav.music.domain.model.TrackId>) {
    days.forEach { (day, tracks) ->
        item(key = "d_$day") {
            Row(Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.gutter, top = Space.xl, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
                Text(day, style = ArnavTheme.type.label, color = ArnavTheme.colors.contentMuted, modifier = Modifier.weight(1f))
                val app = LocalAppViewModel.current
                Text("Take me back", style = ArnavTheme.type.label, color = ArnavTheme.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(Radius.s)).clickable { app.play(tracks, 0) }.padding(Space.xs))
            }
        }
        itemsIndexed(tracks, key = { _, t -> "h_${day}_${t.id.value}" }) { i, t ->
            val app = LocalAppViewModel.current
            val nav = LocalNavigator.current
            TrackRow(t, { app.play(tracks, i) }, playing = t.id == playingId, liked = t.id in liked, onQueue = { app.addToQueue(t) }, onLike = { app.toggleLike(t) },
                onMore = { nav.openSheet(SheetRequest.TrackActions(t)) })
        }
    }
}

/** A song from a Spotify/CSV import that has no YouTube match yet: dimmed, tap to match now. */
@Composable
private fun PendingRow(row: PendingMatchEntity, matching: Boolean, onTap: (() -> Unit)?, onRemove: (() -> Unit)?) {
    val c = ArnavTheme.colors
    val tappable = !row.failed && !matching && onTap != null
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = tappable, onClickLabel = "Match now", role = Role.Button) { onTap?.invoke() }
            .padding(horizontal = Space.gutter, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).alpha(if (row.failed) 0.5f else 0.6f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(Radius.s)).background(c.surfaceRaised), contentAlignment = Alignment.Center) {
                Icon(if (row.failed) Icons.Rounded.SearchOff else Icons.Rounded.HourglassEmpty, null, tint = c.contentSubtle, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(row.title, style = ArnavTheme.type.body, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = when {
                    matching -> "Matching…"
                    row.failed -> "No match found"
                    else -> "Waiting to match"
                }
                Text(
                    listOf(row.artist, status).filter { it.isNotBlank() }.joinToString(" · "),
                    style = ArnavTheme.type.caption, color = c.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            matching -> Box(Modifier.size(Space.touch), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
            }
            row.failed && onRemove != null -> ArnavIconButton(Icons.Rounded.Close, "Remove “${row.title}”", onRemove, tint = c.contentMuted)
        }
    }
}

@Composable
private fun ErrorBlock(error: MusicError) {
    val (t, b) = when (error) {
        MusicError.Offline -> "You're offline" to "This playlist needs a connection the first time. It'll be saved after that."
        MusicError.QuotaExhausted -> "YouTube is resting for today" to "Today's free YouTube allowance is used up. Saved playlists still open."
        MusicError.MissingApiKey -> "YouTube isn't connected" to "Add a YouTube Data API key in Settings → Sources."
        MusicError.Unavailable -> "Not available" to "This collection no longer exists."
        else -> "Couldn't load this" to "Something went wrong. Please try again in a moment."
    }
    EmptyState(Icons.Rounded.MusicOff, t, b)
}
