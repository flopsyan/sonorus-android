package eu.flopsyan.sonorus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import eu.flopsyan.sonorus.data.model.ScanState
import eu.flopsyan.sonorus.ui.AppViewModel
import eu.flopsyan.sonorus.ui.Fmt
import eu.flopsyan.sonorus.ui.components.Chip
import eu.flopsyan.sonorus.ui.components.Progress
import eu.flopsyan.sonorus.ui.components.RackLabelText
import eu.flopsyan.sonorus.ui.components.SonorusButton
import eu.flopsyan.sonorus.ui.theme.SonorusTheme
import eu.flopsyan.sonorus.ui.theme.ThemeMode
import eu.flopsyan.sonorus.ui.theme.num
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A panel with a front-panel label, the way every section of the web app looks. */
@Composable
private fun Panel(label: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = SonorusTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        RackLabelText(label)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun Readout(label: String, value: String) {
    val colors = SonorusTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textDim)
        Text(value, style = num(14.sp), color = colors.text)
    }
}

// --- Settings ---------------------------------------------------------------

@UnstableApi
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val colors = SonorusTheme.colors
    val scope = rememberCoroutineScope()
    val theme by vm.theme.collectAsState()
    var scan by remember { mutableStateOf<ScanState?>(null) }
    var lastScan by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { vm.api.scanState() }.onSuccess {
            scan = it.scan
            lastScan = it.lastScan
        }
    }

    // While a scan runs the state is polled, the same 600 ms the web app uses.
    LaunchedEffect(scan?.running) {
        while (scan?.running == true) {
            delay(600)
            runCatching { vm.api.scanState() }.onSuccess {
                scan = it.scan
                lastScan = it.lastScan
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Panel("Bibliothek") {
            val state = scan
            Readout("Songs", Fmt.number(vm.bootstrap?.stats?.tracks ?: 0))
            Readout("Alben", Fmt.number(vm.bootstrap?.stats?.albums ?: 0))
            Readout("Interpreten", Fmt.number(vm.bootstrap?.stats?.artists ?: 0))
            Readout("Singles", Fmt.number(vm.bootstrap?.stats?.singles ?: 0))
            vm.bootstrap?.stats?.missing?.takeIf { it > 0 }?.let {
                Readout("Fehlende Dateien", Fmt.number(it))
            }
            Readout("Größe", Fmt.bytes(vm.bootstrap?.stats?.size ?: 0))
            if (lastScan != null) Readout("Letzter Scan", Fmt.dateTime(lastScan))

            if (state != null && state.running) {
                Text(
                    "${state.phase}: ${Fmt.number(state.done)} von ${Fmt.number(state.total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textDim,
                )
                // During "walking" the total is unknown, so the bar sweeps
                // rather than claiming 0 %.
                Progress(state.done, state.total, indeterminate = state.phase == "walking")
            } else {
                SonorusButton("Bibliothek scannen", primary = true) {
                    scope.launch {
                        runCatching { vm.api.startScan() }
                            .onSuccess { scan = it.scan }
                            .onFailure { vm.say(vm.message(it), true) }
                    }
                }
            }
            state?.error?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.danger)
            }
        }

        Panel("Darstellung") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Dunkel", theme == ThemeMode.DARK) { vm.setTheme(ThemeMode.DARK) }
                Chip("Hell", theme == ThemeMode.LIGHT) { vm.setTheme(ThemeMode.LIGHT) }
                Chip("Auto", theme == ThemeMode.AUTO) { vm.setTheme(ThemeMode.AUTO) }
            }
        }

        Panel("Konto") {
            Readout("Angemeldet als", vm.bootstrap?.user?.displayName.orEmpty())
            Readout("Server", vm.api.serverUrl)
            SonorusButton("Abmelden", danger = true) { vm.logout() }
        }
    }
}

// --- Statistics -------------------------------------------------------------

@UnstableApi
@Composable
fun StatsScreen(vm: AppViewModel) {
    val stats = vm.bootstrap?.stats
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Panel("Bibliothek") {
            Readout("Songs", Fmt.number(stats?.tracks ?: 0))
            Readout("Interpreten", Fmt.number(stats?.artists ?: 0))
            Readout("Alben", Fmt.number(stats?.albums ?: 0))
            Readout("Singles", Fmt.number(stats?.singles ?: 0))
            Readout("Genres", Fmt.number(stats?.genres ?: 0))
            Readout("Spielzeit", Fmt.durationRack(stats?.duration ?: 0.0))
            Readout("Größe", Fmt.bytes(stats?.size ?: 0))
        }
    }
}

// --- Profile ----------------------------------------------------------------

@UnstableApi
@Composable
fun ProfileScreen(vm: AppViewModel) {
    val user = vm.bootstrap?.user
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Panel("Profil") {
            Readout("Name", user?.displayName.orEmpty())
            Readout("Benutzername", "@${user?.username.orEmpty()}")
            Readout("Rolle", if (user?.isAdmin == true) "Administrator" else "Benutzer")
        }
        Panel("Verbindung") {
            Readout("Server", vm.api.serverUrl)
            SonorusButton("Abmelden", danger = true) { vm.logout() }
        }
    }
}
