package com.arnav.music.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.arnav.music.core.playback.PlayerState
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.queue.QueueItem
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.Artwork
import com.arnav.music.ui.components.Pill
import com.arnav.music.ui.components.TrackRow
import com.arnav.music.ui.components.bounce
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import java.text.DateFormat
import java.util.Date

/**
 * Up Next. Long-press-free reordering via the drag handle: the held row floats (scale + shadow)
 * while neighbours glide out of its way. "Journey" shows upcoming songs as a flowing timeline.
 */
@Composable
fun QueuePanel(
    state: PlayerState,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onSkipTo: (Int) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onShuffle: (() -> Unit)? = null,
    onHarmonicMix: (() -> Unit)? = null,
) {
    val c = ArnavTheme.colors
    val haptics = ArnavTheme.haptics
    val motion = ArnavTheme.motion
    var journey by rememberSaveable { mutableStateOf(false) }
    val q = state.queue
    val latestQueue by rememberUpdatedState(q)
    val listState = rememberLazyListState()
    var draggingUid by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(top = Space.s), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(c.content.copy(alpha = 0.25f)))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Up next", style = ArnavTheme.type.headline, color = c.content)
                val remaining = q.upNext.sumOf { it.track.durationMs ?: 0L }
                Text("${q.upNext.size} tracks · ${Formatters.longDuration(remaining)}", style = ArnavTheme.type.bodySmall, color = c.contentMuted)
            }
            ArnavIconButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "Save queue as playlist", onSave)
        }
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = Space.gutter), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Space.s)) {
            Pill("List", !journey, { journey = false }, leading = Icons.AutoMirrored.Rounded.ViewList)
            Pill("Journey", journey, { journey = true }, leading = Icons.Rounded.Timeline)
            // Reordering happens in place: every row glides to its new slot.
            if (onShuffle != null) Pill("Shuffle", q.shuffled, { haptics.select(); onShuffle() }, leading = Icons.Rounded.Shuffle)
            if (onHarmonicMix != null) Pill("Harmonic mix", false, { haptics.select(); onHarmonicMix() }, leading = Icons.Rounded.GraphicEq)
        }
        Spacer(Modifier.height(Space.s))
        if (q.upNext.isEmpty()) {
            Text("Nothing queued. Swipe right on any song to add it here.", style = ArnavTheme.type.bodySmall, color = c.contentMuted, modifier = Modifier.padding(Space.gutter))
            return@Column
        }
        val bounce = com.arnav.music.ui.components.rememberBounce()
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = Space.xxxl), modifier = Modifier.weight(1f).bounce(bounce)) {
            if (journey) {
                val start = System.currentTimeMillis() + ((q.current?.track?.durationMs ?: 0L) / 2)
                val etas = q.upNext.runningFold(start) { acc, it -> acc + (it.track.durationMs ?: 210_000L) }
                itemsIndexed(q.upNext, key = { _, it -> it.uid }) { i, item ->
                    JourneyNode(item, etas[i], first = i == 0, last = i == q.upNext.lastIndex, onClick = { onSkipTo(q.currentIndex + 1 + i) })
                }
            } else {
                itemsIndexed(q.upNext, key = { _, it -> it.uid }) { i, item ->
                    val absolute = q.currentIndex + 1 + i
                    val dragging = draggingUid == item.uid
                    val lift by animateFloatAsState(if (dragging) 1f else 0f, motion.responsive(), label = "lift")
                    Row(
                        Modifier
                            .then(if (dragging) Modifier else Modifier.animateItem(placementSpec = spring(dampingRatio = 0.8f, stiffness = 280f, visibilityThreshold = IntOffset(1, 1))))
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (dragging) dragOffset else 0f
                                scaleX = 1f + 0.03f * lift; scaleY = 1f + 0.03f * lift
                                shadowElevation = 16.dp.toPx() * lift
                            }
                            .background(c.surfaceRaised.copy(alpha = lift))
                            .semantics {
                                customActions = listOf(
                                    CustomAccessibilityAction("Move up") { if (absolute > q.currentIndex + 1) onMove(absolute, absolute - 1); true },
                                    CustomAccessibilityAction("Move down") { if (absolute < q.items.lastIndex) onMove(absolute, absolute + 1); true },
                                    CustomAccessibilityAction("Remove from queue") { onRemove(absolute); true },
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TrackRow(item.track, onClick = { onSkipTo(absolute) }, modifier = Modifier.weight(1f), compact = true)
                        ArnavIconButton(Icons.Rounded.Close, "Remove ${item.track.title} from queue", { onRemove(absolute) }, tint = c.contentSubtle, size = 18.dp)
                        Icon(
                            Icons.Rounded.DragHandle, "Reorder ${item.track.title}", tint = c.contentSubtle,
                            modifier = Modifier
                                .padding(end = Space.s)
                                .size(Space.touch)
                                .padding(12.dp)
                                .pointerInput(item.uid) {
                                    detectVerticalDragGestures(
                                        onDragStart = { draggingUid = item.uid; dragOffset = 0f; haptics.longPress() },
                                        onDragEnd = { draggingUid = null; dragOffset = 0f; haptics.queued() },
                                        onDragCancel = { draggingUid = null; dragOffset = 0f },
                                    ) { change, dy ->
                                        change.consume()
                                        dragOffset += dy
                                        val cur = latestQueue.items.indexOfFirst { it.uid == item.uid }
                                        val h = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == item.uid }?.size?.toFloat() ?: return@detectVerticalDragGestures
                                        if (dragOffset > h * 0.55f && cur < latestQueue.items.lastIndex) {
                                            onMove(cur, cur + 1); dragOffset -= h; haptics.snap()
                                        } else if (dragOffset < -h * 0.55f && cur > latestQueue.currentIndex + 1) {
                                            onMove(cur, cur - 1); dragOffset += h; haptics.snap()
                                        }
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JourneyNode(item: QueueItem, eta: Long, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val c = ArnavTheme.colors
    val energy = item.track.energy
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.m)).clickable(onClick = onClick).padding(horizontal = Space.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp).height(76.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2
                if (!first) drawLine(c.accent.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height / 2), strokeWidth = 2.dp.toPx())
                if (!last) drawLine(c.accent.copy(alpha = 0.25f), Offset(x, size.height / 2), Offset(x, size.height), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                // Node size hints at energy when known.
                val r = (4f + 4f * (energy ?: 0.4f)).dp.toPx()
                drawCircle(c.accent, r, Offset(x, size.height / 2))
                drawCircle(c.background, r * 0.45f, Offset(x, size.height / 2))
            }
        }
        Spacer(Modifier.width(Space.m))
        Artwork(item.track.artworkUrl, item.track.id.value, Modifier.size(48.dp), RoundedCornerShape(Radius.s), decodeSize = 140)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(item.track.title, style = ArnavTheme.type.titleSmall, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.track.artist, style = ArnavTheme.type.bodySmall, color = c.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(eta)), style = ArnavTheme.type.numeric, color = c.contentSubtle)
    }
}
