package com.arnav.music.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.arnav.music.ui.theme.ArnavTheme

/** Press compression: content dips to [scale] immediately on touch, springs back on release. */
fun Modifier.pressScale(interaction: MutableInteractionSource, scale: Float = 0.96f): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val motion = ArnavTheme.motion
    val s by animateFloatAsState(if (pressed && !motion.reduced) scale else 1f, if (pressed) motion.instant() else motion.expressive(), label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
}

/** Skeleton shimmer. A slow, low-contrast sweep — loading should feel calm, not busy. */
fun Modifier.shimmer(): Modifier = composed {
    val colors = ArnavTheme.colors
    val motion = ArnavTheme.motion
    val base = colors.content.copy(alpha = if (colors.isDark) 0.06f else 0.07f)
    val hi = colors.content.copy(alpha = if (colors.isDark) 0.12f else 0.12f)
    if (motion.reduced) return@composed drawWithCache { onDrawBehind { drawRect(base) } }
    val t by rememberInfiniteTransition(label = "shimmer").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    drawWithCache {
        val w = size.width
        onDrawBehind {
            val x = -w + 3 * w * t
            drawRect(Brush.linearGradient(listOf(base, hi, base), start = Offset(x, 0f), end = Offset(x + w, size.height)))
        }
    }
}

@Composable
fun rememberInteraction() = remember { MutableInteractionSource() }

fun Color.scale(alpha: Float) = copy(alpha = this.alpha * alpha)
