package org.sonorus.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.sonorus.data.download.OfflineCollection
import org.sonorus.data.model.DynamicOptions
import org.sonorus.data.model.FilterOption
import org.sonorus.data.model.PlaylistResponse
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.FilterKind
import org.sonorus.ui.FilterState
import org.sonorus.ui.Fmt
import org.sonorus.ui.Motion
import org.sonorus.ui.Routes
import org.sonorus.ui.ShuffleDefault
import org.sonorus.ui.components.Chip
import org.sonorus.ui.components.RackLabelText
import org.sonorus.ui.components.TextPromptDialog
import org.sonorus.ui.components.TrackList
import org.sonorus.ui.components.albumCovers
import org.sonorus.ui.of
import org.sonorus.ui.pressable
import org.sonorus.ui.rememberShuffle
import org.sonorus.ui.theme.SonorusTheme
import org.sonorus.ui.theme.num
import java.text.Normalizer
import java.time.Instant

private const val DAY_MS = 24L * 60 * 60 * 1000

// "" is the server's own order: interpret, then album, then track.
private val DYNAMIC_SORTS = listOf(
    "" to "Standard",
    "title" to "Titel",
    "artist" to "Interpret",
    "album" to "Album",
    "year" to "Jahr",
    "duration" to "Dauer",
    "added" to "Hinzugefügt",
    "stars" to "Bewertung",
)

/**
 * A playlist made of filters. The songs are whatever passes them, asked for
 * again after every change; a new one lives for a day unless it is kept.
 * Offline the ordinary page shows its download instead - the filters need the
 * server.
 */
@OptIn(ExperimentalLayoutApi::class)
@UnstableApi
@Composable
fun DynamicPlaylistPage(vm: AppViewModel, first: PlaylistResponse, onGo: (String) -> Unit) {
    val colors = SonorusTheme.colors
    val scope = rememberCoroutineScope()
    val id = first.playlist.id
    val key = Routes.playlist(id)
    var playlist by remember(id) { mutableStateOf(first.playlist) }
    var tracks by remember(id) { mutableStateOf(first.tracks) }
    val options by produceState<DynamicOptions?>(null, id) {
        value = runCatching { vm.api.dynamicOptions() }.getOrNull()
    }
    var filters by remember(id, options) { mutableStateOf(options?.let { FilterState.from(playlist.rules, it) }) }
    // Counters rather than flags: every change restarts the effect below, which
    // is what makes a burst of taps one request.
    var edits by remember(id) { mutableIntStateOf(0) }
    var resorts by remember(id) { mutableIntStateOf(0) }
    var sort by rememberSaveable(id) { mutableStateOf("") }
    var dir by rememberSaveable(id) { mutableStateOf("asc") }
    var showFilters by rememberSaveable(id) { mutableStateOf(true) }
    var open by rememberSaveable(id) { mutableStateOf(listOf<String>()) }
    var renaming by remember { mutableStateOf(false) }
    // null while closed; true when the download asked for it.
    var saving by remember { mutableStateOf<Boolean?>(null) }
    val player by vm.player.state.collectAsState()
    val shuffle = rememberShuffle(ShuffleDefault.PLAYLIST)
    val temporary = playlist.expiresAt.isNotEmpty()

    suspend fun reload() {
        runCatching { vm.api.playlist(id, sort, dir) }.onSuccess {
            playlist = it.playlist
            tracks = it.tracks
        }
    }

    LaunchedEffect(edits) {
        if (edits == 0) return@LaunchedEffect
        delay(400)
        val state = filters ?: return@LaunchedEffect
        val opts = options ?: return@LaunchedEffect
        runCatching { vm.api.setPlaylistRules(id, state.payload(opts), sort, dir) }
            .onSuccess { res ->
                playlist = res.playlist
                tracks = res.tracks
                vm.applyPlaylistTree(res.tree)
                vm.dynamicChanged(id)
            }
            .onFailure { vm.say("Die Filter konnten nicht gespeichert werden.", true) }
    }
    LaunchedEffect(resorts) { if (resorts > 0) reload() }

    val end = remember(playlist.expiresAt) {
        playlist.expiresAt.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(end) {
        while (end != null) {
            now = System.currentTimeMillis()
            if (now >= end) break
            delay(1000 - now % 1000)
        }
    }
    val left = end?.let { (it - now).coerceAtLeast(0L) }
    LaunchedEffect(left == 0L) {
        if (left == 0L) {
            vm.say("Die dynamische Playlist ist abgelaufen.")
            vm.refreshQuietly()
            onGo(Routes.HOME)
        }
    }

    TrackList(
        tracks = tracks,
        currentTrackId = player.current?.id,
        currentFromHere = player.sourceKey == key,
        actions = trackActions(vm, tracks, playlist.name, key, onGo, shuffle = shuffle.value),
        // The app draws edge to edge, so the keyboard would cover the year and
        // search fields without this.
        modifier = Modifier.imePadding(),
        showAlbum = true,
        emptyNote = "Kein Song passt zu diesen Filtern.",
        header = {
            Column {
                DetailHead(
                    title = playlist.name,
                    meta = listOf(
                        Fmt.plural(tracks.size, "Song", "Songs"),
                        Fmt.durationLong(tracks.sumOf { it.duration }),
                    ).joinToString(" · "),
                    coverUrls = albumCovers(tracks).mapNotNull { vm.coverUrl(it) },
                    onPlay = { vm.playCollection(tracks, playlist.name, key, shuffle.value) },
                    shuffle = shuffle.value,
                    onToggleShuffle = { shuffle.value = !shuffle.value },
                    onEdit = { renaming = true },
                    // A day-long list is not worth a download: keeping it comes first.
                    download = {
                        if (temporary) {
                            IconButton(onClick = { saving = true }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.Download, "Herunterladen", tint = colors.textDim, modifier = Modifier.size(24.dp))
                            }
                        } else {
                            CollectionDownload(vm, tracks, OfflineCollection(kind = "playlist", id = id, name = playlist.name))
                        }
                    },
                    extra = if (temporary) {
                        {
                            IconButton(onClick = { saving = false }, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.Filled.Save, "Speichern", tint = colors.textDim, modifier = Modifier.size(24.dp))
                            }
                        }
                    } else null,
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (left != null) {
                        TimerRing(left, 62.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            RackLabelText("Dynamische Playlist")
                            Text(
                                "Verlängern",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.accent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .pressable {
                                        vm.extendPlaylist(id) { playlist = it }
                                    }
                                    .padding(vertical = 6.dp),
                            )
                        }
                    } else {
                        RackLabelText("Playlist · dynamisch")
                    }
                    Spacer(Modifier.weight(1f))
                    val opts = options
                    val state = filters
                    if (opts != null && state != null) {
                        FilterToggle(state.activeCount(opts), showFilters) { showFilters = !showFilters }
                    }
                }
                val opts = options
                val state = filters
                if (showFilters && opts != null && state != null) {
                    FilterPanel(
                        options = opts,
                        state = state,
                        open = open,
                        onOpen = { k -> open = if (k in open) open - k else open + k },
                        onChange = {
                            filters = it
                            edits += 1
                        },
                        onInvalid = { vm.say("Bitte zwei Jahreszahlen wie 2008 und 2012 eingeben.", true) },
                    )
                }
                SortRow(
                    options = DYNAMIC_SORTS,
                    sort = sort,
                    dir = dir,
                    total = tracks.size,
                    onPick = { k, d ->
                        sort = k
                        dir = d
                        resorts += 1
                    },
                )
            }
        },
    )

    if (renaming) {
        TextPromptDialog(
            title = "Playlist umbenennen",
            label = "Name",
            initial = playlist.name,
            selectAll = true,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                vm.renamePlaylist(id, name) { scope.launch { reload() } }
            },
        )
    }
    saving?.let { forDownload ->
        TextPromptDialog(
            title = "Playlist speichern",
            label = "Name der Playlist",
            initial = playlist.name,
            confirmLabel = if (forDownload) "Speichern und laden" else "Speichern",
            selectAll = true,
            hint = if (forDownload) "Zum Herunterladen muss die Playlist fest gespeichert sein." else "",
            onDismiss = { saving = null },
            onConfirm = { name ->
                saving = null
                vm.keepPlaylist(id, name) { kept ->
                    playlist = kept
                    if (forDownload) {
                        vm.downloadCollection(OfflineCollection(kind = "playlist", id = id, name = kept.name), tracks)
                    }
                }
            },
        )
    }
}

private fun clockText(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

/** The day that is left, as a ring that empties, with the time ticking inside. */
@Composable
private fun TimerRing(leftMs: Long, size: Dp) {
    val colors = SonorusTheme.colors
    Box(
        Modifier.size(size).semantics { contentDescription = "Läuft ab in ${clockText(leftMs)}" },
        contentAlignment = Alignment.Center,
    ) {
        Ring((leftMs.toFloat() / DAY_MS).coerceIn(0f, 1f), 4.dp, Modifier.size(size))
        Text(clockText(leftMs), style = num(10.sp), color = colors.text)
    }
}

/** The same ring, small, in front of a temporary list in the playlist library. */
@Composable
fun MiniRing(expiresAt: String, modifier: Modifier = Modifier) {
    val end = remember(expiresAt) { runCatching { Instant.parse(expiresAt).toEpochMilli() }.getOrNull() } ?: return
    val share = ((end - System.currentTimeMillis()).toFloat() / DAY_MS).coerceIn(0f, 1f)
    Ring(share, 2.5.dp, modifier)
}

@Composable
private fun Ring(share: Float, stroke: Dp, modifier: Modifier) {
    val colors = SonorusTheme.colors
    Canvas(modifier) {
        val width = stroke.toPx()
        val inset = width / 2
        val arc = Size(size.width - width, size.height - width)
        drawArc(colors.surface2, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(width))
        drawArc(colors.accent, -90f, 360f * share, false, Offset(inset, inset), arc, style = Stroke(width, cap = StrokeCap.Round))
    }
}

/** Shows and hides the filters: the symbol, and how many filters are on. */
@Composable
private fun FilterToggle(active: Int, shown: Boolean, onClick: () -> Unit) {
    val colors = SonorusTheme.colors
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (shown) colors.accentSoft else colors.surface2)
            .border(1.dp, if (shown) colors.accentLine else colors.line, shape)
            .pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = if (shown) "Filter ausblenden" else "Filter einblenden" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Tune, null, tint = if (shown) colors.accent else colors.textDim, modifier = Modifier.size(20.dp))
        if (active > 0) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                Text(active.toString(), style = num(11.sp), color = colors.accentInk)
            }
        }
    }
}

@Composable
private fun FilterPanel(
    options: DynamicOptions,
    state: FilterState,
    open: List<String>,
    onOpen: (String) -> Unit,
    onChange: (FilterState) -> Unit,
    onInvalid: () -> Unit,
) {
    val colors = SonorusTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp)
            .clip(shape)
            .border(1.dp, colors.line, shape)
            .background(colors.surface),
    ) {
        FilterKind.entries.forEachIndexed { i, kind ->
            if (i > 0) HorizontalDivider(color = colors.line)
            FilterSection(kind, options, state, kind.key in open, { onOpen(kind.key) }, onChange, onInvalid)
        }
    }
}

private fun fold(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(
    kind: FilterKind,
    options: DynamicOptions,
    state: FilterState,
    open: Boolean,
    onToggle: () -> Unit,
    onChange: (FilterState) -> Unit,
    onInvalid: () -> Unit,
) {
    val colors = SonorusTheme.colors
    val (summary, filtering) = state.summary(kind, options)
    val turn by animateFloatAsState(if (open) 180f else 0f, Motion.quick(), label = "caret")
    Column {
        Row(
            Modifier.fillMaxWidth().pressable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(kind.label, style = MaterialTheme.typography.titleSmall, color = colors.text, modifier = Modifier.width(104.dp))
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (filtering) colors.accent else colors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.ExpandMore, null, tint = colors.textFaint, modifier = Modifier.size(20.dp).rotate(turn))
        }
        if (!open) return@Column
        val all = options.of(kind)
        // The side that makes the filter goes first, decided once per opening so a
        // row never jumps away from under the thumb.
        val order = remember(kind) {
            val on = all.filter { it.id in state.ticked(kind) }
            val off = all.filter { it.id !in state.ticked(kind) }
            if (off.isEmpty() || kind == FilterKind.STARS) all else if (on.size <= off.size) on + off else off + on
        }
        var query by remember(kind) { mutableStateOf("") }
        val visible = if (query.isBlank()) order else order.filter { fold("${it.name} ${it.artist}").contains(fold(query.trim())) }

        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // With a search typed, both mean what the search shows.
                LinkText("Alle") {
                    onChange(
                        if (kind == FilterKind.DECADES) state.with(kind, all.map { it.id }.toSet()).copy(ranges = emptyList())
                        else state.with(kind, state.ticked(kind) + visible.map { it.id })
                    )
                }
                Spacer(Modifier.width(8.dp))
                LinkText("Keine") {
                    onChange(
                        if (kind == FilterKind.DECADES) state.with(kind, emptySet()).copy(ranges = emptyList())
                        else state.with(kind, state.ticked(kind) - visible.map { it.id }.toSet())
                    )
                }
                Spacer(Modifier.weight(1f))
                if (kind != FilterKind.DECADES && kind != FilterKind.STARS) {
                    Text("${state.ticked(kind).size} von ${all.size}", style = num(11.sp), color = colors.textFaint)
                }
            }
            if (kind == FilterKind.DECADES) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (d in all) Chip(d.name, d.id in state.ticked(kind)) { onChange(state.toggle(kind, d.id)) }
                }
                RackLabelText("Eigene Bereiche")
                if (state.ranges.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.ranges.forEachIndexed { i, r ->
                            RangeChip(FilterState.rangeText(r)) {
                                onChange(state.copy(ranges = state.ranges.filterIndexed { j, _ -> j != i }))
                            }
                        }
                    }
                }
                RangeAdd(onAdd = { a, b -> onChange(state.addRange(a, b, options)) }, onInvalid = onInvalid)
            } else {
                if (kind.search.isNotEmpty()) SmallField(query, kind.search, Modifier.fillMaxWidth()) { query = it }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(visible, key = { it.id }) { o ->
                        CheckRow(o, o.id in state.ticked(kind), kind == FilterKind.ALBUMS) { onChange(state.toggle(kind, o.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = SonorusTheme.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).pressable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

@Composable
private fun CheckRow(option: FilterOption, checked: Boolean, withArtist: Boolean, onToggle: () -> Unit) {
    val colors = SonorusTheme.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = colors.accent,
                uncheckedColor = colors.textDim,
                checkmarkColor = colors.accentInk,
            ),
        )
        Column(Modifier.weight(1f)) {
            Text(option.name, style = MaterialTheme.typography.bodyMedium, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = if (withArtist) listOfNotNull(option.artist.ifEmpty { null }, option.year?.toString()).joinToString(" · ") else ""
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun RangeChip(label: String, onRemove: () -> Unit) {
    val colors = SonorusTheme.colors
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .clip(shape)
            .background(colors.accentSoft)
            .border(1.dp, colors.accentLine, shape)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = num(13.sp), color = colors.accent)
        IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Filled.Close, "Bereich $label entfernen", tint = colors.accent, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun RangeAdd(onAdd: (Int, Int) -> Unit, onInvalid: () -> Unit) {
    val colors = SonorusTheme.colors
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallField(from, "2008", Modifier.width(92.dp), numeric = true) { from = it.filter(Char::isDigit).take(4) }
        Text("bis", style = MaterialTheme.typography.bodyMedium, color = colors.textDim)
        SmallField(to, "2012", Modifier.width(92.dp), numeric = true) { to = it.filter(Char::isDigit).take(4) }
        IconButton(onClick = {
            val a = from.toIntOrNull()
            val b = (to.ifEmpty { from }).toIntOrNull()
            if (a == null || b == null || a !in 1000..2999 || b !in 1000..2999) {
                onInvalid()
            } else {
                onAdd(a, b)
                from = ""
                to = ""
            }
        }) {
            Icon(Icons.Filled.Add, "Bereich hinzufügen", tint = colors.accent)
        }
    }
}

@Composable
private fun SmallField(value: String, hint: String, modifier: Modifier, numeric: Boolean = false, onChange: (String) -> Unit) {
    val colors = SonorusTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(hint, color = colors.textFaint) },
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.line,
            focusedContainerColor = colors.surface2,
            unfocusedContainerColor = colors.surface2,
            focusedTextColor = colors.text,
            unfocusedTextColor = colors.text,
            cursorColor = colors.accent,
        ),
        modifier = modifier,
    )
}
