package com.arnav.music.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.arnav.music.domain.model.Track
import com.arnav.music.ui.player.SheetRequest

/** Thin navigation facade handed to screens, so they never touch the NavController directly. */
class Navigator(
    private val nav: NavHostController,
    val openSheet: (SheetRequest) -> Unit,
    val openPlayer: () -> Unit,
    val openPalette: () -> Unit,
    val share: (Track) -> Unit,
    /** Opens Now Playing with the artwork flying from [bounds] (root coordinates, px). */
    val flyFrom: (androidx.compose.ui.geometry.Rect) -> Unit = {},
) {
    private var lastRoute: String? = null
    private var lastAt = 0L

    /**
     * Pushes a screen. Not single-top: same-pattern routes with different arguments (artist → artist,
     * settings → settings/appearance) must stack so Back returns to the previous one. Rapid
     * double-taps on the same target are ignored instead.
     */
    fun go(route: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (route == lastRoute && now - lastAt < 600) return
        lastRoute = route; lastAt = now
        nav.navigate(route)
    }
    fun back() { if (!nav.popBackStack()) Unit }
    fun topLevel(route: String) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }
val LocalAppViewModel = staticCompositionLocalOf<AppViewModel> { error("AppViewModel not provided") }
/** Space reserved at the bottom of scrolling content for the MorphBar + navigation chrome. */
val LocalChromePadding = staticCompositionLocalOf { PaddingValues(bottom = 160.dp) }
