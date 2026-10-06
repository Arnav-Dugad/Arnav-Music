package com.arnav.music.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.arnav.music.ui.theme.ArnavTheme

/** The app-wide shared-transition scope (set around the NavHost). */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The current destination's enter/exit scope, so artwork can travel between screens. */
val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Stable shared-element keys for artwork that should carry from a card into its detail page. */
object ArtKeys {
    fun playlist(id: String) = "art-playlist-$id"
    fun smart(kind: String) = "art-smart-$kind"
    fun artist(name: String) = "art-artist-${name.lowercase()}"
    fun collection(kind: CollectionKind, id: String) = when (kind) {
        CollectionKind.SMART -> smart(id)
        else -> "art-${kind.name.lowercase()}-$id"
    }
}

/**
 * Marks artwork as the same element across screens: tapping a playlist/mix card makes its cover
 * glide and grow into the page's hero (and back on return). No-op without a transition scope or
 * under reduced motion.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedArt(key: String?): Modifier {
    if (key == null || ArnavTheme.motion.reduced) return this
    val shared = LocalSharedScope.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    return with(shared) { this@sharedArt.sharedElement(rememberSharedContentState(key), anim) }
}
