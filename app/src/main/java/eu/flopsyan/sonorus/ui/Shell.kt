package eu.flopsyan.sonorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import eu.flopsyan.sonorus.data.model.Bootstrap
import eu.flopsyan.sonorus.data.model.Playlist
import eu.flopsyan.sonorus.ui.components.ConfirmDialog
import eu.flopsyan.sonorus.ui.components.PlaylistPickerDialog
import eu.flopsyan.sonorus.ui.components.TextPromptDialog
import eu.flopsyan.sonorus.ui.components.PlayerBar
import eu.flopsyan.sonorus.ui.components.RackLabelText
import eu.flopsyan.sonorus.ui.screens.FullPlayer
import eu.flopsyan.sonorus.ui.theme.SonorusTheme
import eu.flopsyan.sonorus.ui.theme.num
import kotlinx.coroutines.launch

/**
 * The app around the pages: the library tree in a drawer, the transport across
 * the bottom, and the full-screen player over both.
 *
 * The transport is one player, not two: the full screen is the same state shown
 * larger, exactly the decision the web app made ("there is no second player"),
 * which is why nothing here can drift out of step with the bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun Shell(vm: AppViewModel, data: Bootstrap) {
    val colors = SonorusTheme.colors
    val nav = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val playerState by vm.player.state.collectAsState()
    val toast by vm.toast.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it.text)
            vm.clearToast()
        }
    }

    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = colors.surface,
                drawerContentColor = colors.text,
            ) {
                Sidebar(
                    vm = vm,
                    data = data,
                    onGo = { target ->
                        scope.launch { drawer.close() }
                        nav.navigate(target) { launchSingleTop = true }
                    },
                )
            }
        },
    ) {
        Scaffold(
            containerColor = colors.bg,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            titleFor(route, data),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Filled.Menu, "Menü", tint = colors.text)
                        }
                    },
                    actions = {
                        IconButton(onClick = { nav.navigate(Routes.SEARCH) { launchSingleTop = true } }) {
                            Icon(Icons.Filled.Search, "Suche", tint = colors.text)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = colors.bg,
                        titleContentColor = colors.text,
                    ),
                )
            },
            bottomBar = {
                val current = playerState.current
                if (current != null) {
                    Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                        PlayerBar(
                            track = current,
                            playing = playerState.playing,
                            positionMs = playerState.positionMs,
                            durationMs = playerState.durationMs,
                            coverUrl = vm.api.coverUrl(current.cover),
                            onToggle = { vm.player.toggle() },
                            onNext = { vm.player.next() },
                            onPrevious = { vm.player.previous() },
                            onExpand = { expanded = true },
                            onSeek = { f ->
                                val d = playerState.durationMs
                                if (d > 0) vm.player.seekTo((d * f).toLong())
                            },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                SonorusNavHost(vm, nav)
            }
        }
    }

    val pending by vm.pendingAdd.collectAsState()
    var newListForTrack by remember { mutableStateOf(false) }

    pending?.let { track ->
        if (newListForTrack) {
            TextPromptDialog(
                title = "Neue Playlist",
                label = "Name",
                confirmLabel = "Anlegen",
                onDismiss = { newListForTrack = false },
                onConfirm = { name ->
                    newListForTrack = false
                    vm.createPlaylistWithTrack(name, track)
                },
            )
        } else {
            PlaylistPickerDialog(
                tree = data.playlists,
                onDismiss = { vm.cancelAdd() },
                onNew = { newListForTrack = true },
                onPick = { id, name ->
                    vm.cancelAdd()
                    vm.addToPlaylist(id, track.id, name)
                },
            )
        }
    }

    val editingSingle by vm.editingSingle.collectAsState()
    editingSingle?.let { track ->
        eu.flopsyan.sonorus.ui.screens.EditSingleDialog(
            vm = vm,
            track = track,
            onDismiss = { vm.closeSingleEditor() },
            onSaved = { vm.refreshQuietly() },
        )
    }

    if (expanded && playerState.current != null) {
        FullPlayer(
            vm = vm,
            state = playerState,
            onClose = { expanded = false },
            onGo = { target ->
                expanded = false
                nav.navigate(target) { launchSingleTop = true }
            },
        )
    }
}

private fun titleFor(route: String?, data: Bootstrap): String = when (route) {
    Routes.TRACKS -> "Alle Songs"
    Routes.ARTISTS -> "Interpreten"
    Routes.ALBUMS -> "Alben"
    Routes.GENRES -> "Genres"
    Routes.SEARCH -> "Suche"
    Routes.SETTINGS -> "Einstellungen"
    Routes.STATS -> "Statistik"
    Routes.PROFILE -> "Profil"
    else -> data.siteName
}

@OptIn(ExperimentalFoundationApi::class)
@UnstableApi
@Composable
private fun Sidebar(vm: AppViewModel, data: Bootstrap, onGo: (String) -> Unit) {
    val colors = SonorusTheme.colors
    // What the long press opened, if anything.
    var menuFor by remember { mutableStateOf<Playlist?>(null) }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    var newList by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renamingFolder by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var deletingFolder by remember { mutableStateOf<Pair<Int, String>?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(data.siteName, style = MaterialTheme.typography.headlineSmall, color = colors.text)
        }
        Spacer(Modifier.height(8.dp))

        SidebarLabel("Bibliothek")
        SidebarRow(Icons.Filled.Home, "Start") { onGo(Routes.HOME) }
        SidebarRow(Icons.Filled.MusicNote, "Alle Songs", data.stats.tracks) { onGo(Routes.TRACKS) }
        SidebarRow(Icons.Filled.Person, "Interpreten", data.stats.artists) { onGo(Routes.ARTISTS) }
        SidebarRow(Icons.Filled.Album, "Alben", data.stats.albums) { onGo(Routes.ALBUMS) }
        SidebarRow(Icons.Filled.Category, "Genres", data.stats.genres) { onGo(Routes.GENRES) }

        SidebarLabel("Bewertungen")
        // 5 down to 1, then "Nicht bewertet" - the order the web sidebar uses.
        for (value in listOf(5, 4, 3, 2, 1, 0)) {
            SidebarRow(
                icon = Icons.Filled.Star,
                label = starLabel(value),
                count = data.stars[value.toString()] ?: 0,
            ) { onGo(Routes.stars(listOf(value))) }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RackLabelText("Playlists")
            Row {
                IconButton(onClick = { newList = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.PlaylistAdd, "Neue Playlist",
                        tint = colors.textDim, modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = { newFolder = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.CreateNewFolder, "Neuer Ordner",
                        tint = colors.textDim, modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        for (folder in data.playlists.folders) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { renamingFolder = folder.id to folder.name },
                    )
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                RackLabelText(folder.name)
                IconButton(
                    onClick = { deletingFolder = folder.id to folder.name },
                    modifier = Modifier.size(26.dp),
                ) {
                    Icon(
                        Icons.Filled.Close, "Ordner löschen",
                        tint = colors.textFaint, modifier = Modifier.size(14.dp),
                    )
                }
            }
            for (p in folder.playlists) {
                PlaylistRow(p, onGo = onGo) { menuFor = p }
            }
        }
        for (p in data.playlists.loose) {
            PlaylistRow(p, onGo = onGo) { menuFor = p }
        }
        if (data.playlists.folders.isEmpty() && data.playlists.loose.isEmpty()) {
            Text(
                "Noch keine Playlist. Lang auf eine tippen öffnet ihr Menü.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textFaint,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        SidebarLabel("System")
        SidebarRow(Icons.Filled.BarChart, "Statistik") { onGo(Routes.STATS) }
        SidebarRow(
            icon = Icons.Filled.Notifications,
            label = "Mitteilungen",
            count = data.issues.takeIf { it > 0 },
        ) { onGo(Routes.NOTICES) }
        SidebarRow(Icons.Filled.Settings, "Einstellungen") { onGo(Routes.SETTINGS) }
        SidebarRow(Icons.Filled.Person, data.user.displayName.ifEmpty { data.user.username }) {
            onGo(Routes.PROFILE)
        }
    }

    menuFor?.let { p ->
        PlaylistMenu(
            playlist = p,
            onDismiss = { menuFor = null },
            onOpen = { menuFor = null; onGo(Routes.playlist(p.id)) },
            onRename = { menuFor = null; renaming = p },
            onPin = { menuFor = null; vm.pinPlaylist(p.id, !p.pinned) },
            onDelete = { menuFor = null; deleting = p },
        )
    }
    renaming?.let { p ->
        TextPromptDialog(
            title = "Playlist umbenennen",
            label = "Name",
            initial = p.name,
            onDismiss = { renaming = null },
            onConfirm = { name -> renaming = null; vm.renamePlaylist(p.id, name) },
        )
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = "Playlist löschen",
            message = "\"${p.name}\" wird endgültig gelöscht. Die Songs selbst bleiben erhalten.",
            onDismiss = { deleting = null },
            onConfirm = { deleting = null; vm.deletePlaylist(p.id) },
        )
    }
    if (newList) {
        TextPromptDialog(
            title = "Neue Playlist",
            label = "Name",
            confirmLabel = "Anlegen",
            onDismiss = { newList = false },
            onConfirm = { name -> newList = false; vm.createPlaylist(name) },
        )
    }
    if (newFolder) {
        TextPromptDialog(
            title = "Neuer Ordner",
            label = "Name",
            confirmLabel = "Anlegen",
            onDismiss = { newFolder = false },
            onConfirm = { name -> newFolder = false; vm.createFolder(name) },
        )
    }
    renamingFolder?.let { (id, name) ->
        TextPromptDialog(
            title = "Ordner umbenennen",
            label = "Name",
            initial = name,
            onDismiss = { renamingFolder = null },
            onConfirm = { next -> renamingFolder = null; vm.renameFolder(id, next) },
        )
    }
    deletingFolder?.let { (id, name) ->
        ConfirmDialog(
            title = "Ordner löschen",
            // Worth saying out loud, because it is not what "delete" usually means.
            message = "\"$name\" wird gelöscht. Die Playlists darin bleiben erhalten und rücken nach oben.",
            onDismiss = { deletingFolder = null },
            onConfirm = { deletingFolder = null; vm.deleteFolder(id) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(p: Playlist, onGo: (String) -> Unit, onMenu: () -> Unit) {
    val colors = SonorusTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = { onGo(Routes.playlist(p.id)) },
                onLongClick = onMenu,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A pinned list wears the pin instead of the list icon - the marker and
        // the position say the same thing twice, on purpose.
        Icon(
            if (p.pinned) Icons.Filled.PushPin else Icons.AutoMirrored.Filled.List,
            null,
            tint = if (p.pinned) colors.accent else colors.textDim,
            modifier = Modifier.size(18.dp),
        )
        Text(
            p.name,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(Fmt.number(p.trackCount), style = num(11.sp), color = colors.textFaint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistMenu(
    playlist: Playlist,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = SonorusTheme.colors
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            SheetItem(Icons.AutoMirrored.Filled.List, "Öffnen", onOpen)
            SheetItem(
                Icons.Filled.PushPin,
                if (playlist.pinned) "Nicht mehr anheften" else "Anheften",
                onPin,
            )
            SheetItem(Icons.Filled.Edit, "Umbenennen", onRename)
            SheetItem(Icons.Filled.Delete, "Löschen", onDelete, danger = true)
        }
    }
}

@Composable
private fun SheetItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val colors = SonorusTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = if (danger) colors.danger else colors.textDim)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) colors.danger else colors.text,
        )
    }
}

@Composable
private fun SidebarLabel(text: String, small: Boolean = false) {
    Spacer(Modifier.height(if (small) 8.dp else 16.dp))
    RackLabelText(text, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SidebarRow(
    icon: ImageVector,
    label: String,
    count: Int? = null,
    onClick: () -> Unit,
) {
    val colors = SonorusTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = colors.textDim, modifier = Modifier.size(18.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text(Fmt.number(count), style = num(11.sp), color = colors.textFaint)
        }
    }
}
