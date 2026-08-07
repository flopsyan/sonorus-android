package org.sonorus.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import org.sonorus.ui.screens.AccountsScreen
import org.sonorus.ui.screens.AlbumScreen
import org.sonorus.ui.screens.AlbumsScreen
import org.sonorus.ui.screens.ArtistScreen
import org.sonorus.ui.screens.ArtistSinglesScreen
import org.sonorus.ui.screens.ArtistStarsScreen
import org.sonorus.ui.screens.ArtistsScreen
import org.sonorus.ui.screens.DownloadsScreen
import org.sonorus.ui.screens.GenreScreen
import org.sonorus.ui.screens.GenresScreen
import org.sonorus.ui.screens.HomeScreen
import org.sonorus.ui.screens.NoticesScreen
import org.sonorus.ui.screens.PlaylistScreen
import org.sonorus.ui.screens.ProfileScreen
import org.sonorus.ui.screens.SearchScreen
import org.sonorus.ui.screens.SettingsScreen
import org.sonorus.ui.screens.StarsScreen
import org.sonorus.ui.screens.StatsScreen
import org.sonorus.ui.screens.TracksScreen

/** Reads a comma list like `5,4` or `1,4` out of a route argument. */
private fun idList(raw: String?): List<Int> =
    raw.orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }

/**
 * How far a page slides. A fraction of the width rather than all of it: a whole
 * screen travelling past reads as slow no matter how short the animation is,
 * while a short slide under a fade says "this came from over there" and is over
 * before it can be in the way.
 */
private const val SLIDE = 6

/**
 * The tabs along the bottom are *places*, not steps, so moving between them must
 * not look like going forward. They cross-fade with a hair of scale instead -
 * the same gesture a deck makes when a card is exchanged rather than dealt.
 */
private val TABS = setOf(
    Routes.HOME, Routes.TRACKS, Routes.ARTISTS, Routes.ALBUMS, Routes.GENRES,
)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs() =
    targetState.destination.route in TABS && initialState.destination.route in TABS

@UnstableApi
@Composable
fun SonorusNavHost(vm: AppViewModel, nav: NavHostController) {
    val go: (String) -> Unit = { nav.navigate(it) { launchSingleTop = true } }

    // Without these the default applies, and the default is a 700 ms cross-fade
    // on *every* page change - which is most of what made the app feel heavy.
    // Nothing here is slower than 240 ms.
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = {
            if (betweenTabs()) fadeIn(Motion.entering()) + scaleIn(Motion.entering(), initialScale = 0.98f)
            else slideInHorizontally(Motion.standard()) { it / SLIDE } + fadeIn(Motion.entering())
        },
        exitTransition = {
            if (betweenTabs()) fadeOut(Motion.quick()) + scaleOut(Motion.standard(), targetScale = 1.02f)
            else slideOutHorizontally(Motion.standard()) { -it / SLIDE } + fadeOut(Motion.quick())
        },
        // Going back runs the same move the other way round, so the page that
        // was left slides back in from the side it left towards.
        popEnterTransition = {
            if (betweenTabs()) fadeIn(Motion.entering()) + scaleIn(Motion.entering(), initialScale = 0.98f)
            else slideInHorizontally(Motion.standard()) { -it / SLIDE } + fadeIn(Motion.entering())
        },
        popExitTransition = {
            if (betweenTabs()) fadeOut(Motion.quick()) + scaleOut(Motion.standard(), targetScale = 1.02f)
            else slideOutHorizontally(Motion.standard()) { it / SLIDE } + fadeOut(Motion.quick())
        },
    ) {
        composable(Routes.HOME) { HomeScreen(vm, go) }
        composable(Routes.TRACKS) { TracksScreen(vm, go) }
        composable(Routes.ARTISTS) { ArtistsScreen(vm, go) }
        composable(Routes.ALBUMS) { AlbumsScreen(vm, go) }
        composable(Routes.GENRES) { GenresScreen(vm, go) }
        composable(Routes.SEARCH) { SearchScreen(vm, go) }
        composable(Routes.DOWNLOADS) { DownloadsScreen(vm, go) }
        composable(Routes.SETTINGS) { SettingsScreen(vm, go) }
        composable(Routes.NOTICES) { NoticesScreen(vm) }
        composable(Routes.ACCOUNTS) { AccountsScreen(vm) }
        composable(Routes.STATS) { StatsScreen(vm) }
        composable(Routes.PROFILE) { ProfileScreen(vm) }

        composable(
            Routes.ARTIST,
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
        ) { ArtistScreen(vm, it.arguments?.getInt("id") ?: 0, go) }

        composable(
            Routes.ARTIST_SINGLES,
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
        ) { ArtistSinglesScreen(vm, it.arguments?.getInt("id") ?: 0, go) }

        composable(
            Routes.ARTIST_STARS,
            arguments = listOf(
                navArgument("id") { type = NavType.IntType },
                navArgument("stars") { type = NavType.StringType },
            ),
        ) {
            ArtistStarsScreen(
                vm,
                it.arguments?.getInt("id") ?: 0,
                idList(it.arguments?.getString("stars")),
                go,
            )
        }

        composable(
            Routes.ALBUM,
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
        ) { AlbumScreen(vm, it.arguments?.getInt("id") ?: 0, go) }

        // A comma list arrives as a String; Express matched `1,4` under `:id`
        // all along, and the app describes it the same way.
        composable(
            Routes.GENRE,
            arguments = listOf(navArgument("ids") { type = NavType.StringType }),
        ) { GenreScreen(vm, idList(it.arguments?.getString("ids")), go) }

        composable(
            Routes.STARS,
            arguments = listOf(navArgument("stars") { type = NavType.StringType }),
        ) { StarsScreen(vm, idList(it.arguments?.getString("stars")), go) }

        composable(
            Routes.PLAYLIST,
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
        ) { PlaylistScreen(vm, it.arguments?.getInt("id") ?: 0, go) }
    }
}
