package eu.flopsyan.sonorus.ui

import androidx.compose.runtime.Composable
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import eu.flopsyan.sonorus.ui.screens.AlbumScreen
import eu.flopsyan.sonorus.ui.screens.AlbumsScreen
import eu.flopsyan.sonorus.ui.screens.ArtistScreen
import eu.flopsyan.sonorus.ui.screens.ArtistSinglesScreen
import eu.flopsyan.sonorus.ui.screens.ArtistStarsScreen
import eu.flopsyan.sonorus.ui.screens.ArtistsScreen
import eu.flopsyan.sonorus.ui.screens.GenreScreen
import eu.flopsyan.sonorus.ui.screens.GenresScreen
import eu.flopsyan.sonorus.ui.screens.HomeScreen
import eu.flopsyan.sonorus.ui.screens.PlaylistScreen
import eu.flopsyan.sonorus.ui.screens.ProfileScreen
import eu.flopsyan.sonorus.ui.screens.SearchScreen
import eu.flopsyan.sonorus.ui.screens.SettingsScreen
import eu.flopsyan.sonorus.ui.screens.StarsScreen
import eu.flopsyan.sonorus.ui.screens.StatsScreen
import eu.flopsyan.sonorus.ui.screens.TracksScreen

/** Reads a comma list like `5,4` or `1,4` out of a route argument. */
private fun idList(raw: String?): List<Int> =
    raw.orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }

@UnstableApi
@Composable
fun SonorusNavHost(vm: AppViewModel, nav: NavHostController) {
    val go: (String) -> Unit = { nav.navigate(it) { launchSingleTop = true } }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(vm, go) }
        composable(Routes.TRACKS) { TracksScreen(vm, go) }
        composable(Routes.ARTISTS) { ArtistsScreen(vm, go) }
        composable(Routes.ALBUMS) { AlbumsScreen(vm, go) }
        composable(Routes.GENRES) { GenresScreen(vm, go) }
        composable(Routes.SEARCH) { SearchScreen(vm, go) }
        composable(Routes.SETTINGS) { SettingsScreen(vm) }
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
