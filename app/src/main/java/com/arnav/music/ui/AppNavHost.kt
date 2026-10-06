package com.arnav.music.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.arnav.music.feature.ai.ArnavAiScreen
import com.arnav.music.feature.auth.AuthScreen
import com.arnav.music.feature.collection.ArtistScreen
import com.arnav.music.feature.collection.CollectionScreen
import com.arnav.music.feature.explore.ExploreScreen
import com.arnav.music.feature.explore.SearchScreen
import com.arnav.music.feature.home.HomeScreen
import com.arnav.music.feature.insights.ConstellationScreen
import com.arnav.music.feature.insights.InsightsScreen
import com.arnav.music.feature.insights.TimelineScreen
import com.arnav.music.feature.library.LibraryScreen
import com.arnav.music.feature.moments.MomentScreen
import com.arnav.music.feature.profile.ProfileScreen
import com.arnav.music.feature.settings.SettingsScreen
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Easing

@Composable
fun AppNavHost(nav: NavHostController) {
    val motion = ArnavTheme.motion
    val travel = motion.travel
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        // Spatial hierarchy: deeper screens slide in from the trailing edge with a slight
        // depth shift; top-level tabs crossfade in place.
        enterTransition = {
            val top = targetState.destination.route in Routes.topLevel
            if (top) fadeIn(motion.fast()) + scaleIn(motion.fast(), 0.985f)
            else slideInHorizontally(motion.offsetSpring()) { (it * 0.18f * travel).toInt() } + fadeIn(androidx.compose.animation.core.tween(220, easing = Easing.Emphasized))
        },
        exitTransition = {
            val top = targetState.destination.route in Routes.topLevel
            if (top) fadeOut(motion.fast()) else fadeOut(motion.fast()) + scaleOut(motion.fast(), 0.97f)
        },
        popEnterTransition = { fadeIn(motion.fast()) + scaleIn(motion.fast(), 0.97f) },
        popExitTransition = { slideOutHorizontally(motion.offsetSpring()) { (it * 0.18f * travel).toInt() } + fadeOut(motion.fast()) },
    ) {
        composable(Routes.HOME) { HomeScreen() }
        composable(Routes.EXPLORE) { ExploreScreen() }
        composable(Routes.LIBRARY) { LibraryScreen() }
        composable(Routes.AI, arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) {
            ArnavAiScreen(initialQuery = it.arguments?.getString("q").orEmpty())
        }
        composable(Routes.SEARCH, arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) {
            SearchScreen(initialQuery = it.arguments?.getString("q").orEmpty())
        }
        composable(Routes.COLLECTION, arguments = listOf(navArgument("kind") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType })) {
            val kind = runCatching { CollectionKind.valueOf(it.arguments?.getString("kind").orEmpty()) }.getOrDefault(CollectionKind.LIKED)
            CollectionScreen(kind, it.arguments?.getString("id").orEmpty())
        }
        composable(Routes.ARTIST, arguments = listOf(navArgument("name") { type = NavType.StringType })) {
            ArtistScreen(it.arguments?.getString("name").orEmpty())
        }
        composable(Routes.MOMENT, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            MomentScreen(it.arguments?.getString("id").orEmpty())
        }
        composable(Routes.INSIGHTS) { InsightsScreen() }
        composable(Routes.CONSTELLATION) { ConstellationScreen() }
        composable(Routes.TIMELINE) { TimelineScreen() }
        composable(Routes.PROFILE) { ProfileScreen() }
        composable(Routes.AUTH) { AuthScreen() }
        composable(Routes.SETTINGS, arguments = listOf(navArgument("page") { type = NavType.StringType; defaultValue = "" })) {
            SettingsScreen(page = it.arguments?.getString("page").orEmpty())
        }
    }
}
