package com.arnav.music.ui.lyrics

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.arnav.music.core.lyrics.LyricsRepository
import com.arnav.music.core.lyrics.LyricsState
import com.arnav.music.core.playback.Progress
import com.arnav.music.domain.lyrics.LyricLine
import com.arnav.music.domain.lyrics.LyricWord
import com.arnav.music.domain.lyrics.Lyrics
import com.arnav.music.domain.lyrics.LyricsTiming
import com.arnav.music.domain.lyrics.displayLines
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Radius
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.abs

/**
 * Apple Music–style lyrics for Now Playing. Lyrics come only from the user's own file, an
 * imported .lrc/.txt, or pasted text (see [LyricsRepository]).
 *
 * Time-synced lyrics follow playback: the sung line sits ~30% from the top, lines below follow
 * the scroll in a short cascade, word-synced lines fill word by word, and instrumental breaks
 * show breathing dots. Dragging the list pauses following for three seconds.
 */
@Composable
fun LyricsPanel(
    track: Track,
    progress: Progress,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    on: Color,
    muted: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    LyricsHost(track, live = true, progress, isPlaying, onSeek, on, muted, accent, modifier, contentPadding)
}

/** All lyric lines at full opacity, without following playback (e.g. for a song that isn't playing). */
@Composable
fun StaticLyricsPanel(
    track: Track,
    on: Color,
    muted: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    LyricsHost(track, live = false, Progress(), isPlaying = false, onSeek = {}, on, muted, accent, modifier, contentPadding)
}

// region host

private const val MAX_PASTE_CHARS = 200_000

@Composable
private fun LyricsHost(
    track: Track,
    live: Boolean,
    progress: Progress,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    on: Color,
    muted: Color,
    accent: Color,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val repo = koinInject<LyricsRepository>()
    val flow = remember(track.id) { repo.observe(track) }
    val state by flow.collectAsState(initial = LyricsState.Loading)
    val scope = rememberCoroutineScope()
    val haptics = ArnavTheme.haptics

    var editorText by remember(track.id) { mutableStateOf<String?>(null) }
    var editorError by remember(track.id) { mutableStateOf<String?>(null) }
    var notice by remember(track.id) { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val ok = repo.importFile(track, uri)
                notice = if (ok) null else "That file didn't contain lyrics Arnav Music could read."
            }
        }
    }
    val openPicker: () -> Unit = {
        notice = null
        val launched = runCatching { picker.launch(arrayOf("text/*", "application/octet-stream")) }.isSuccess
        if (!launched) notice = "No file picker is available on this device."
    }
    val openEditor: (String) -> Unit = { initial ->
        editorError = null
        editorText = initial
    }
    val remove: () -> Unit = {
        haptics.destructive()
        scope.launch { repo.remove(track) }
    }
    val rescanFile: () -> Unit = {
        scope.launch {
            val found = repo.rescanEmbedded(track)
            notice = if (found) null else "The song file doesn't contain lyrics."
        }
    }
    val rescan: (() -> Unit)? = if (track.source == SourceType.LOCAL) rescanFile else null

    Box(modifier) {
        AnimatedContent(
            targetState = state,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
            label = "lyrics",
            contentKey = { s ->
                when (s) {
                    LyricsState.Loading -> 0
                    LyricsState.None -> 1
                    is LyricsState.Ready -> if (s.lyrics is Lyrics.Synced && live) 2 else 3
                }
            },
        ) { s ->
            when (s) {
                LyricsState.Loading -> LoadingBars(on, contentPadding)
                LyricsState.None -> NoLyrics(
                    track = track,
                    on = on,
                    muted = muted,
                    accent = accent,
                    contentPadding = contentPadding,
                    notice = notice,
                    onImport = openPicker,
                    onPaste = { openEditor("") },
                    onRescan = rescan,
                )
                is LyricsState.Ready -> {
                    val menu: @Composable () -> Unit = {
                        LyricsMenu(
                            tint = muted,
                            onEdit = { openEditor(s.raw) },
                            onImport = openPicker,
                            onRemove = remove,
                        )
                    }
                    val lyrics = s.lyrics
                    if (lyrics is Lyrics.Synced && live) {
                        SyncedLyrics(
                            lines = lyrics.lines,
                            progress = progress,
                            isPlaying = isPlaying,
                            onSeek = onSeek,
                            on = on,
                            muted = muted,
                            accent = accent,
                            contentPadding = contentPadding,
                            menu = menu,
                        )
                    } else {
                        val caption = when {
                            lyrics is Lyrics.Plain -> "Not time-synced · ${sourceLabel(s.source)}"
                            else -> "Time-synced · ${sourceLabel(s.source)}"
                        }
                        StaticLyrics(
                            lines = lyrics.displayLines(),
                            caption = caption,
                            notice = notice,
                            on = on,
                            muted = muted,
                            contentPadding = contentPadding,
                            menu = menu,
                        )
                    }
                }
            }
        }
    }

    val initial = editorText
    if (initial != null) {
        LyricsEditorDialog(
            initial = initial,
            title = if (initial.isBlank()) "Paste lyrics" else "Edit lyrics",
            error = editorError,
            onDismiss = { editorText = null },
            onSave = { text ->
                scope.launch {
                    val ok = repo.savePasted(track, text)
                    if (ok) {
                        haptics.select()
                        editorText = null
                        notice = null
                    } else {
                        editorError = "Nothing to save. Paste the words, optionally with [mm:ss.xx] timestamps."
                    }
                }
            },
        )
    }
}

private fun sourceLabel(source: String): String = when (source) {
    LyricsRepository.SOURCE_EMBEDDED -> "From the song file"
    LyricsRepository.SOURCE_FILE -> "Imported by you"
    LyricsRepository.SOURCE_PASTED -> "Added by you"
    else -> "Saved on this device"
}

// endregion

// region synced

private const val INACTIVE_ALPHA = 0.35f
private const val BROWSING_ALPHA = 0.55f
private const val UNSUNG_ALPHA = 0.42f
private const val INACTIVE_SCALE = 0.97f
private const val ANCHOR_FRACTION = 0.3f
private const val RESUME_FOLLOW_MS = 3_000L

/** Playback position extrapolated every frame between the coarse [Progress] updates. */
@Stable
private class LyricClock(initial: Long) {
    var positionMs by mutableLongStateOf(initial)
        private set
    private var anchorPos = initial
    private var anchorNs = System.nanoTime()
    private var playing = false
    private var durationMs = 0L
    private var seekTarget = -1L
    private var seekGuardUntilNs = 0L

    fun sync(pos: Long, duration: Long, isPlaying: Boolean, now: Long) {
        durationMs = duration
        playing = isPlaying
        // A report from before a tap-to-seek: keep the optimistic position for a moment.
        if (seekTarget >= 0 && now < seekGuardUntilNs && abs(pos - seekTarget) > 1_500) return
        seekTarget = -1
        anchorPos = pos
        anchorNs = now
        if (isPlaying) tick(now) else positionMs = pos
    }

    fun tick(now: Long) {
        if (!playing) return
        var target = anchorPos + (now - anchorNs) / 1_000_000L
        if (durationMs > 0) target = target.coerceAtMost(durationMs)
        val shown = positionMs
        // Never step backwards by a few frames' worth when a fresh report lands slightly behind.
        positionMs = if (target < shown && shown - target < 400) shown else target
    }

    fun seek(ms: Long, now: Long) {
        seekTarget = ms
        seekGuardUntilNs = now + 1_500_000_000L
        anchorPos = ms
        anchorNs = now
        positionMs = ms
    }
}

/**
 * The cascade: when the list advances, it jumps instantly and every row is drawn offset back to
 * where it was, then each row glides into place, rows below the sung line starting a little later.
 * Offsets are a pure function of time, so rows composed mid-cascade join in seamlessly.
 */
@Stable
private class Cascade {
    private class Pulse(val delta: Float, val startNs: Long, val anchor: Int)

    private val pulses = ArrayList<Pulse>()
    private var job: Job? = null
    var nowNs by mutableLongStateOf(0L)
        private set

    fun offsetFor(index: Int): Float {
        val now = nowNs
        if (pulses.isEmpty()) return 0f
        var sum = 0f
        for (p in pulses) {
            val step = (index - p.anchor).coerceIn(0, MAX_STEPS)
            val t = (now - p.startNs) / 1_000_000f - step * STEP_MS
            val x = (t / DURATION_MS).coerceIn(0f, 1f)
            sum += p.delta * (1f - Curve.transform(x))
        }
        return sum
    }

    fun push(delta: Float, anchor: Int, scope: CoroutineScope) {
        val now = System.nanoTime()
        pulses.add(Pulse(delta, now, anchor))
        nowNs = now
        if (job?.isActive == true) return
        job = scope.launch {
            while (pulses.isNotEmpty()) {
                withFrameNanos { }
                val t = System.nanoTime()
                pulses.removeAll { (t - it.startNs) / 1_000_000f > DURATION_MS + MAX_STEPS * STEP_MS }
                nowNs = t
            }
        }
    }

    fun clear() {
        pulses.clear()
        nowNs = System.nanoTime()
    }

    companion object {
        const val MAX_STEPS = 4
        const val STEP_MS = 42f
        const val DURATION_MS = 620f
        val Curve = CubicBezierEasing(0.22f, 0.1f, 0.0f, 1.0f)
    }
}

private class LayoutRef {
    var value: TextLayoutResult? = null
}

@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    progress: Progress,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    on: Color,
    muted: Color,
    accent: Color,
    contentPadding: PaddingValues,
    menu: @Composable () -> Unit,
) {
    val motion = ArnavTheme.motion
    val haptics = ArnavTheme.haptics
    val budget = ArnavTheme.budget
    val reduced = motion.reduced
    val allowBlur = !reduced && Build.VERSION.SDK_INT >= 31 && budget?.realBlur != false
    val lineStyle = ArnavTheme.type.display.copy(
        fontSize = 30.sp,
        lineHeight = 34.5.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.02).em,
    )
    val followSpec = motion.responsive<Float>()

    val clock = remember(lines) { LyricClock(progress.positionMs) }
    LaunchedEffect(clock, progress, isPlaying) {
        clock.sync(progress.positionMs, progress.durationMs, isPlaying, System.nanoTime())
        if (isPlaying) {
            while (true) {
                withFrameNanos { }
                clock.tick(System.nanoTime())
            }
        }
    }
    val active by remember(lines, clock) { derivedStateOf { LyricsTiming.activeIndex(lines, clock.positionMs) } }

    val listState = rememberLazyListState()
    val cascade = remember(lines) { Cascade() }
    val scope = rememberCoroutineScope()

    // Manual browsing pauses following; it resumes 3 s after the last touch / fling.
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var browsing by remember { mutableStateOf(false) }
    LaunchedEffect(dragged) {
        if (dragged) {
            browsing = true
            cascade.clear()
        }
    }
    val scrolling = listState.isScrollInProgress
    LaunchedEffect(browsing, dragged, scrolling) {
        if (browsing && !dragged && !scrolling) {
            delay(RESUME_FOLLOW_MS)
            browsing = false
        }
    }

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullHeight: Dp = if (constraints.hasBoundedHeight) maxHeight else 640.dp
        val padTop = contentPadding.calculateTopPadding()
        val padBottom = contentPadding.calculateBottomPadding()
        val visible = (fullHeight - padTop - padBottom).coerceAtLeast(120.dp)
        val anchor = padTop + visible * ANCHOR_FRACTION
        val anchorPx = with(density) { anchor.toPx() }
        val heightPx = with(density) { fullHeight.toPx() }
        val fadeTopStart = with(density) { padTop.toPx() }
        val fadeTopEnd = fadeTopStart + with(density) { 56.dp.toPx() }
        val fadeBottomEnd = heightPx - with(density) { padBottom.toPx() }
        val fadeBottomStart = fadeBottomEnd - with(density) { 72.dp.toPx() }

        val lastFollowed = remember(lines) { IntArray(1) { -2 } }
        LaunchedEffect(active, browsing, lines, anchorPx) {
            if (browsing) return@LaunchedEffect
            val target = active.coerceAtLeast(0)
            val previous = lastFollowed[0]
            lastFollowed[0] = target
            val firstAlign = previous == -2
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == target }
            if (item == null) {
                if (firstAlign || reduced) listState.scrollToItem(target) else listState.animateScrollToItem(target)
                return@LaunchedEffect
            }
            val delta = (item.offset - info.viewportStartOffset).toFloat() - anchorPx
            if (abs(delta) < 1f) return@LaunchedEffect
            when {
                firstAlign -> listState.scrollBy(delta)
                reduced -> listState.animateScrollBy(delta, followSpec)
                target > previous && previous >= 0 && delta > 0f && delta < heightPx * 0.8f -> {
                    val consumed = listState.scrollBy(delta)
                    if (consumed != 0f) cascade.push(consumed, target, scope)
                }
                else -> listState.animateScrollBy(delta, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow))
            }
        }

        val start = contentPadding.calculateStartPadding(layoutDirection)
        val end = contentPadding.calculateEndPadding(layoutDirection)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(fadeTopStart, fadeTopEnd, fadeBottomStart, fadeBottomEnd),
            contentPadding = PaddingValues(
                start = start + 18.dp,
                end = end + 18.dp,
                top = anchor,
                bottom = (fullHeight - anchor).coerceAtLeast(0.dp),
            ),
        ) {
            itemsIndexed(lines, key = { i, l -> "${l.startMs}:$i" }) { index, line ->
                val current = active
                val isActive = index == current
                val distance = if (current < 0) index + 1 else abs(index - current)
                val onTap: () -> Unit = {
                    haptics.select()
                    clock.seek(line.startMs, System.nanoTime())
                    browsing = false
                    onSeek(line.startMs)
                }
                if (line.isInstrumental) {
                    InstrumentalRow(line, index, isActive, clock, cascade, on, reduced, onTap)
                } else {
                    LyricRow(
                        line = line,
                        index = index,
                        isActive = isActive,
                        distance = distance,
                        browsing = browsing,
                        clock = clock,
                        cascade = cascade,
                        allowBlur = allowBlur,
                        reduced = reduced,
                        on = on,
                        style = lineStyle,
                        onTap = onTap,
                    )
                }
            }
        }

        Box(Modifier.align(Alignment.TopEnd).padding(top = padTop, end = end + 4.dp)) { menu() }
    }
}

@Composable
private fun LyricRow(
    line: LyricLine,
    index: Int,
    isActive: Boolean,
    distance: Int,
    browsing: Boolean,
    clock: LyricClock,
    cascade: Cascade,
    allowBlur: Boolean,
    reduced: Boolean,
    on: Color,
    style: TextStyle,
    onTap: () -> Unit,
) {
    val alphaTarget = when {
        isActive -> 1f
        browsing -> BROWSING_ALPHA
        else -> INACTIVE_ALPHA
    }
    val lineAlpha by animateFloatAsState(alphaTarget, tween(if (reduced) 160 else 420), label = "lineAlpha")
    val scale by animateFloatAsState(
        if (isActive || reduced) 1f else INACTIVE_SCALE,
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow),
        label = "lineScale",
    )
    val blurTarget = if (!allowBlur || browsing || isActive) 0f else (distance.coerceAtMost(4) * 0.7f)
    val blurDp by animateFloatAsState(blurTarget, tween(380), label = "lineBlur")
    val words = line.words
    val wordSync = isActive && words.isNotEmpty()
    val ranges = remember(line) { wordRanges(line) }
    val layoutRef = remember { LayoutRef() }
    val interaction = remember { MutableInteractionSource() }

    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = cascade.offsetFor(index)
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
                alpha = lineAlpha
                val radius = blurDp.dp.toPx()
                renderEffect = if (radius > 0.3f) BlurEffect(radius, radius, TileMode.Decal) else null
            }
            .clip(RoundedCornerShape(Radius.m))
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = on),
                onClickLabel = "Seek here",
                role = Role.Button,
                onClick = onTap,
            )
            .semantics {
                contentDescription = line.text
                if (isActive) stateDescription = "Now singing"
                customActions = listOf(CustomAccessibilityAction("Seek here") { onTap(); true })
            }
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Text(
            text = line.text,
            style = style,
            color = on,
            modifier = if (wordSync) {
                Modifier
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val layout = layoutRef.value
                        if (layout != null) drawWordMask(layout, ranges, words, line, clock.positionMs)
                    }
            } else Modifier,
            onTextLayout = { layoutRef.value = it },
        )
    }
}

/** Character ranges [start, end) of each timed word inside the line text; -1 when not found. */
private fun wordRanges(line: LyricLine): IntArray {
    val out = IntArray(line.words.size * 2) { -1 }
    var cursor = 0
    line.words.forEachIndexed { i, w ->
        if (w.text.isEmpty()) return@forEachIndexed
        val at = line.text.indexOf(w.text, cursor)
        if (at >= 0) {
            out[2 * i] = at
            out[2 * i + 1] = at + w.text.length
            cursor = at + w.text.length
        }
    }
    return out
}

/**
 * Masks the (already drawn, full-brightness) line so sung text stays bright and unsung text is dim,
 * with a soft edge moving through the current word. Wrap-aware: each visual text line is handled.
 */
private fun DrawScope.drawWordMask(
    layout: TextLayoutResult,
    ranges: IntArray,
    words: List<LyricWord>,
    line: LyricLine,
    positionMs: Long,
) {
    val textLength = line.text.length
    if (textLength == 0 || layout.lineCount == 0) return
    var current = -1
    for (i in words.indices) {
        if (words[i].startMs <= positionMs) current = i else break
    }
    val fillLine: Int
    val fillX: Float
    if (current < 0) {
        fillLine = -1
        fillX = 0f
    } else {
        var s = ranges.getOrElse(2 * current) { -1 }
        var e = ranges.getOrElse(2 * current + 1) { -1 }
        val p = LyricsTiming.wordProgress(words[current], positionMs)
        if (s < 0 || e < 0) {
            // Word not found in the text: fall back to an even sweep over the whole line.
            val c = (textLength * LyricsTiming.lineProgress(line, positionMs)).toInt().coerceIn(0, textLength)
            s = c
            e = c
        }
        val l = layout.getLineForOffset(s.coerceIn(0, textLength))
        val lineEnd = layout.getLineEnd(l, visibleEnd = true)
        val x0 = layout.getHorizontalPosition(s.coerceIn(0, textLength), true)
        val x1 = layout.getHorizontalPosition(e.coerceIn(s, maxOf(s, lineEnd)).coerceIn(0, textLength), true)
        fillLine = l
        fillX = x0 + (x1 - x0) * p
    }
    val feather = 14.dp.toPx()
    val pad = 4.dp.toPx()
    val dim = Color.Black.copy(alpha = UNSUNG_ALPHA)
    for (l in 0 until layout.lineCount) {
        if (l < fillLine) continue // fully sung
        val top = layout.getLineTop(l)
        val bottom = layout.getLineBottom(l)
        val left = layout.getLineLeft(l) - pad
        val right = layout.getLineRight(l) + pad
        val width = right - left
        if (width <= 0f || bottom <= top) continue
        val topLeft = Offset(left, top)
        val rectSize = Size(width, bottom - top)
        if (l > fillLine) {
            drawRect(color = dim, topLeft = topLeft, size = rectSize, blendMode = BlendMode.DstIn)
        } else {
            val a = ((fillX - feather / 2f - left) / width).coerceIn(0f, 1f)
            val b = ((fillX + feather / 2f - left) / width).coerceIn(a, 1f)
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black,
                    a to Color.Black,
                    b to dim,
                    1f to dim,
                    startX = left,
                    endX = right,
                ),
                topLeft = topLeft,
                size = rectSize,
                blendMode = BlendMode.DstIn,
            )
        }
    }
}

@Composable
private fun InstrumentalRow(
    line: LyricLine,
    index: Int,
    isActive: Boolean,
    clock: LyricClock,
    cascade: Cascade,
    on: Color,
    reduced: Boolean,
    onTap: () -> Unit,
) {
    val heightSpec: FiniteAnimationSpec<Dp> =
        if (reduced) tween(160) else spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)
    val height by animateDpAsState(if (isActive) 64.dp else 10.dp, heightSpec, label = "breakHeight")
    val visibility by animateFloatAsState(if (isActive) 1f else 0f, tween(if (reduced) 120 else 320), label = "breakAlpha")
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { translationY = cascade.offsetFor(index) }
            .clickable(interactionSource = null, indication = null, onClickLabel = "Seek here", role = Role.Button, onClick = onTap)
            .semantics {
                contentDescription = "Instrumental"
                if (isActive) stateDescription = "Now playing"
                customActions = listOf(CustomAccessibilityAction("Seek here") { onTap(); true })
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (isActive || visibility > 0.01f) {
            BreathingDots(line, clock, on, reduced, Modifier.graphicsLayer { alpha = visibility })
        }
    }
}

@Composable
private fun BreathingDots(line: LyricLine, clock: LyricClock, on: Color, reduced: Boolean, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "dots")
    val breath = transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    Canvas(modifier.size(width = 64.dp, height = 22.dp)) {
        val p = LyricsTiming.lineProgress(line, clock.positionMs)
        // Shrink away during the last moments of the break, just before singing resumes.
        val outro = ((1f - p) / 0.08f).coerceIn(0f, 1f)
        val b = if (reduced) 1f else breath.value
        val r = 5.dp.toPx()
        val gap = 10.dp.toPx()
        val cy = size.height / 2f
        for (i in 0 until 3) {
            val fill = (p * 3f - i).coerceIn(0f, 1f)
            val radius = r * b * (0.9f + 0.1f * fill) * (0.6f + 0.4f * outro)
            val cx = r + i * (2 * r + gap)
            drawCircle(
                color = on.copy(alpha = (0.28f + 0.72f * fill) * (0.4f + 0.6f * outro)),
                radius = radius,
                center = Offset(cx, cy),
            )
        }
    }
}

// endregion

// region static, loading, empty

@Composable
private fun StaticLyrics(
    lines: List<String>,
    caption: String,
    notice: String?,
    on: Color,
    muted: Color,
    contentPadding: PaddingValues,
    menu: @Composable () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    val style = ArnavTheme.type.headline.copy(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold)
    val padTop = contentPadding.calculateTopPadding()
    val padBottom = contentPadding.calculateBottomPadding()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heightPx = with(density) { (if (constraints.hasBoundedHeight) maxHeight else 640.dp).toPx() }
        val topPx = with(density) { padTop.toPx() }
        val bottomPx = heightPx - with(density) { padBottom.toPx() }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(topPx, topPx + with(density) { 12.dp.toPx() }, bottomPx - with(density) { 48.dp.toPx() }, bottomPx),
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection) + 28.dp,
                end = contentPadding.calculateEndPadding(layoutDirection) + 28.dp,
                top = padTop + 4.dp,
                bottom = padBottom + 56.dp,
            ),
        ) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(caption, style = ArnavTheme.type.caption, color = muted, modifier = Modifier.weight(1f))
                        menu()
                    }
                    if (notice != null) {
                        Text(notice, style = ArnavTheme.type.caption, color = on, modifier = Modifier.padding(bottom = 12.dp))
                    }
                }
            }
            itemsIndexed(lines, key = { i, _ -> i }) { _, text ->
                if (text.isBlank()) {
                    Spacer(Modifier.height(18.dp))
                } else {
                    Text(text, style = style, color = on, modifier = Modifier.padding(vertical = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun LoadingBars(on: Color, contentPadding: PaddingValues) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.weight(0.3f))
        listOf(0.72f, 0.9f, 0.55f).forEach { w ->
            Box(
                Modifier
                    .fillMaxWidth(w)
                    .height(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(on.copy(alpha = 0.07f)),
            )
            Spacer(Modifier.height(18.dp))
        }
        Spacer(Modifier.weight(0.7f))
    }
}

@Composable
private fun NoLyrics(
    track: Track,
    on: Color,
    muted: Color,
    accent: Color,
    contentPadding: PaddingValues,
    notice: String?,
    onImport: () -> Unit,
    onPaste: () -> Unit,
    onRescan: (() -> Unit)?,
) {
    val local = track.source == SourceType.LOCAL
    Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Lyrics, null, tint = on, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "No lyrics for this song yet",
                style = ArnavTheme.type.headline.copy(fontSize = 20.sp, lineHeight = 26.sp),
                color = on,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (local) {
                    "There are no lyrics inside this song file. Add them from an .lrc or text file, or paste them — lines with timestamps will follow the music."
                } else {
                    "Lyrics come from your own song files, or you can add them yourself from an .lrc or text file, or by pasting them."
                },
                style = ArnavTheme.type.bodySmall,
                color = muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PanelButton("Import .lrc file", Icons.Rounded.Description, onImport, filled = true, on = on)
                PanelButton("Paste lyrics", Icons.Rounded.ContentPaste, onPaste, filled = false, on = on)
            }
            if (onRescan != null) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onRescan) {
                    Text("Look in the song file again", style = ArnavTheme.type.label, color = muted)
                }
            }
            if (notice != null) {
                Spacer(Modifier.height(6.dp))
                Text(notice, style = ArnavTheme.type.caption, color = on, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Arnav Music never copies lyrics from websites.",
                style = ArnavTheme.type.caption,
                color = muted.copy(alpha = muted.alpha * 0.8f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PanelButton(text: String, icon: ImageVector, onClick: () -> Unit, filled: Boolean, on: Color) {
    val haptics = ArnavTheme.haptics
    val fg = if (filled) (if (on.luminance() > 0.5f) Color(0xFF111116) else Color.White) else on
    Row(
        Modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(if (filled) on else on.copy(alpha = 0.12f))
            .clickable(role = Role.Button) { haptics.press(); onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = ArnavTheme.type.label, color = fg, maxLines = 1)
    }
}

@Composable
private fun LyricsMenu(tint: Color, onEdit: () -> Unit, onImport: () -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ArnavIconButton(Icons.Rounded.MoreHoriz, "Lyrics options", { open = true }, tint = tint, size = 20.dp)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Edit lyrics") },
                onClick = { open = false; onEdit() },
                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
            )
            DropdownMenuItem(
                text = { Text("Import .lrc file") },
                onClick = { open = false; onImport() },
                leadingIcon = { Icon(Icons.Rounded.Description, null) },
            )
            DropdownMenuItem(
                text = { Text("Remove lyrics") },
                onClick = { open = false; onRemove() },
                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
            )
        }
    }
}

@Composable
private fun LyricsEditorDialog(
    initial: String,
    title: String,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val c = ArnavTheme.colors
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save", color = c.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = c.contentMuted) }
        },
        title = { Text(title, style = ArnavTheme.type.title) },
        text = {
            Column {
                Text(
                    "Plain text works. Add [mm:ss.xx] timestamps (LRC) and the lyrics will follow the song.",
                    style = ArnavTheme.type.bodySmall,
                    color = c.contentMuted,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_PASTE_CHARS) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                    textStyle = ArnavTheme.type.bodySmall.copy(color = c.content),
                    placeholder = { Text("[00:12.00] First line…", style = ArnavTheme.type.bodySmall) },
                    shape = RoundedCornerShape(Radius.m),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.accent,
                        unfocusedBorderColor = c.outline,
                        cursorColor = c.accent,
                    ),
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, style = ArnavTheme.type.caption, color = c.danger)
                }
            }
        },
        shape = RoundedCornerShape(Radius.xl),
        containerColor = c.surfaceRaised,
        titleContentColor = c.content,
        textContentColor = c.content,
    )
}

/** Soft fade at the top/bottom of a scrolling list (positions in px from the top of the list). */
private fun Modifier.fadingEdges(topStart: Float, topEnd: Float, bottomStart: Float, bottomEnd: Float): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = size.height
        if (h <= 0f) return@drawWithContent
        val a = (topStart / h).coerceIn(0f, 1f)
        val b = (topEnd / h).coerceIn(a, 1f)
        val c = (bottomStart / h).coerceIn(b, 1f)
        val d = (bottomEnd / h).coerceIn(c, 1f)
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                a to Color.Transparent,
                b to Color.Black,
                c to Color.Black,
                d to Color.Transparent,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

// endregion
