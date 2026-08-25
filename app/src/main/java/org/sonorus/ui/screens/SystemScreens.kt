package org.sonorus.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import org.sonorus.data.Quality
import org.sonorus.data.model.ScanState
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.Fmt
import org.sonorus.ui.Routes
import org.sonorus.ui.components.Chip
import org.sonorus.ui.components.Progress
import org.sonorus.ui.components.RackLabelText
import org.sonorus.ui.components.SonorusButton
import org.sonorus.ui.theme.SonorusTheme
import org.sonorus.ui.theme.ThemeMode
import org.sonorus.ui.theme.num
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.sonorus.ui.components.ServerOnlyNote
import org.sonorus.ui.LocalOffline

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

@Composable
private fun LinkRow(label: String, hint: String, badge: Int? = null, onClick: () -> Unit) {
    val colors = SonorusTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.text)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.textFaint)
        }
        if (badge != null && badge > 0) {
            Text(
                Fmt.number(badge),
                style = num(11.sp),
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.accentSoft)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.textFaint)
    }
}

/**
 * The scan's phase, in words. The server names them for a log; this is the line
 * a person reads while waiting, and "transcoding" is by far the longest of them.
 */
private fun scanPhase(phase: String): String = when (phase) {
    "walking" -> "Ordner wird gelesen"
    "reading" -> "Dateien werden ausgelesen"
    "pruning" -> "Aufräumen"
    "transcoding" -> "Kleinere Qualität wird erzeugt"
    else -> "Scan läuft"
}

// --- Settings ---------------------------------------------------------------

@UnstableApi
@Composable
fun SettingsScreen(vm: AppViewModel, onGo: (String) -> Unit) {
    val colors = SonorusTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by vm.theme.collectAsState()
    val offline by vm.offline.collectAsState()
    val downloads by vm.downloads.state.collectAsState()
    var scan by remember { mutableStateOf<ScanState?>(null) }
    var lastScan by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    // The client reads the file and posts its text, exactly like the web app -
    // so there is no upload handling and nothing is written to disk.
    val pickCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "CSV-Import"
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } ?: error("Die Datei konnte nicht gelesen werden.")
                vm.api.importCsv(text, name)
            }.onSuccess { answer ->
                val obj = answer.jsonObject
                val added = obj["added"]?.jsonPrimitive?.content ?: "0"
                val open = obj["issues"]?.jsonPrimitive?.content ?: "0"
                vm.say("$added Songs importiert, $open Mitteilung(en) offen.")
                vm.refreshQuietly()
            }.onFailure { vm.say(vm.message(it), true) }
            importing = false
        }
    }

    // Offline there is nothing to ask and nothing that could answer.
    LaunchedEffect(offline) {
        if (offline) return@LaunchedEffect
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
        Panel("Downloads") {
            Readout("Songs auf dem Gerät", Fmt.number(downloads.done.size))
            Readout("Belegt", Fmt.bytes(downloads.bytes))
            LinkRow(
                "Downloads verwalten",
                "Was ohne Verbindung spielt, und wie geladen wird",
            ) { onGo(Routes.DOWNLOADS) }
        }

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

            if (offline) {
                Text(
                    "Offline. Die Zahlen sind die deiner Downloads; scannen und " +
                        "importieren gehen erst wieder mit Verbindung.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textFaint,
                )
            } else if (state != null && state.running) {
                Text(
                    "${scanPhase(state.phase)}: ${Fmt.number(state.done)} von ${Fmt.number(state.total)}",
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

        if (!offline) Panel("Playlists importieren") {
            Text(
                "Eine CSV mit den Spalten playlist, title, artists und album. " +
                    "Die Exporte der üblichen Streamingdienste werden auch erkannt.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textDim,
            )
            SonorusButton(if (importing) "Wird importiert …" else "CSV auswählen", enabled = !importing) {
                // Some providers label CSV as text/comma-separated-values.
                pickCsv.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"))
            }
            LinkRow(
                "Mitteilungen",
                "Zeilen, die kein Song sein konnten",
                badge = vm.bootstrap?.issues,
            ) { onGo(Routes.NOTICES) }
        }

        Panel("Qualität") {
            val streamQuality by vm.streamQuality.collectAsState()
            val downloadQuality by vm.downloadQuality.collectAsState()
            val qualityReady by vm.qualityReady.collectAsState()

            if (!qualityReady) {
                Text(
                    "Dieser Server liefert nur das Original aus - dort ist kein ffmpeg " +
                        "installiert. Es gibt also nichts umzustellen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textFaint,
                )
            } else {
                Text(
                    "Gilt nur für dieses Gerät, nicht fürs Konto. Streamen und " +
                        "Herunterladen getrennt: unterwegs die kleine Kopie hören und " +
                        "trotzdem das Original aufs Gerät legen ist eine ganz normale Antwort.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textDim,
                )
                Text("Streamen", style = MaterialTheme.typography.bodyLarge, color = colors.text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (quality in Quality.entries) {
                        Chip(quality.label, streamQuality == quality) { vm.setStreamQuality(quality) }
                    }
                }
                Text("Herunterladen", style = MaterialTheme.typography.bodyLarge, color = colors.text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (quality in Quality.entries) {
                        Chip(quality.label, downloadQuality == quality) { vm.setDownloadQuality(quality) }
                    }
                }
                // The promise worth writing down: this decides what the *next*
                // download asks for and nothing else. A song fetched as FLAC
                // stays a FLAC, and switching here never deletes anything.
                Text(
                    "Was schon heruntergeladen ist, bleibt unverändert - die Einstellung " +
                        "gilt für neue Downloads.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textFaint,
                )
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
            if (!offline) {
                // Admin only, front and back: `GET /api/users` says no to
                // everyone else, so offering the row would lead into an error.
                if (vm.bootstrap?.user?.isAdmin == true) {
                    LinkRow("Konten", "Wer auf diese Instanz zugreift") { onGo(Routes.ACCOUNTS) }
                }
                LinkRow("Profil", "Name, Avatar und Passwort") { onGo(Routes.PROFILE) }
            }
            // Logging out throws the session away, and with it the way back into
            // the downloads without a server. So offline it is not offered.
            if (!offline) SonorusButton("Abmelden", danger = true) { vm.logout() }
        }
    }
}

// --- Profile ----------------------------------------------------------------

@UnstableApi
@Composable
fun ProfileScreen(vm: AppViewModel) {
    if (LocalOffline.current) return ServerOnlyNote("Das Profil")
    val colors = SonorusTheme.colors
    val scope = rememberCoroutineScope()
    val user = vm.bootstrap?.user
    var display by remember { mutableStateOf(user?.displayName.orEmpty()) }
    var avatar by remember { mutableStateOf(user?.avatar.orEmpty()) }
    var currentPass by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Panel("Profil") {
            Readout("Benutzername", "@${user?.username.orEmpty()}")
            Readout("Rolle", if (user?.isAdmin == true) "Administrator" else "Benutzer")
            DialogField("Anzeigename", display) { display = it }
            DialogField("Avatar", avatar, placeholder = "Ein Buchstabe oder Emoji") { avatar = it }
        }

        Panel("Passwort ändern") {
            // The signature binds the password hash, so a change invalidates
            // every existing session - the server hands back a fresh cookie.
            DialogField("Aktuelles Passwort", currentPass, password = true) { currentPass = it }
            DialogField("Neues Passwort", newPass, password = true) { newPass = it }
            Text(
                "Leer lassen, wenn nur der Name geändert werden soll.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textFaint,
            )
        }

        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SonorusButton(
                if (busy) "Wird gespeichert …" else "Speichern",
                primary = true,
                enabled = !busy,
            ) {
                busy = true
                scope.launch {
                    runCatching {
                        vm.api.updateProfile(display.trim(), avatar.trim(), currentPass, newPass)
                    }.onSuccess {
                        currentPass = ""
                        newPass = ""
                        vm.say("Profil gespeichert.")
                        vm.refreshQuietly()
                    }.onFailure { vm.say(vm.message(it), true) }
                    busy = false
                }
            }
        }

        Panel("Verbindung") {
            Readout("Server", vm.api.serverUrl)
            SonorusButton("Abmelden", danger = true) { vm.logout() }
        }
    }
}
