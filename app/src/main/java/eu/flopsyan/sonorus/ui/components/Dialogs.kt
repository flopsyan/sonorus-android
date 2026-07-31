package eu.flopsyan.sonorus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import eu.flopsyan.sonorus.data.model.PlaylistTree
import eu.flopsyan.sonorus.ui.Fmt
import eu.flopsyan.sonorus.ui.theme.SonorusTheme
import eu.flopsyan.sonorus.ui.theme.num

/** The shell every dialog shares: a titled card over a scrim. */
@Composable
fun SonorusDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String? = null,
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = SonorusTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .padding(20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = colors.text)
            Spacer(Modifier.size(14.dp))
            content()
            if (onConfirm != null || confirmLabel != null) {
                Spacer(Modifier.size(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    SonorusButton("Abbrechen", onClick = onDismiss)
                    if (onConfirm != null && confirmLabel != null) {
                        SonorusButton(
                            confirmLabel,
                            primary = true,
                            enabled = confirmEnabled,
                            onClick = onConfirm,
                        )
                    }
                }
            }
        }
    }
}

/** A dialog with one text field - new playlist, rename, new folder. */
@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = "Speichern",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = SonorusTheme.colors
    var text by remember { mutableStateOf(initial) }

    SonorusDialog(
        title = title,
        onDismiss = onDismiss,
        confirmLabel = confirmLabel,
        confirmEnabled = text.isNotBlank(),
        onConfirm = { onConfirm(text.trim()) },
    ) {
        RackLabelText(label)
        Spacer(Modifier.size(6.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.line,
                focusedContainerColor = colors.surface2,
                unfocusedContainerColor = colors.surface2,
                focusedTextColor = colors.text,
                unfocusedTextColor = colors.text,
                cursorColor = colors.accent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Löschen",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = SonorusTheme.colors
    SonorusDialog(title, onDismiss, confirmLabel, onConfirm = onConfirm) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.textDim)
    }
}

/**
 * Picks the playlist a track goes into. Shows the tree the way the sidebar
 * does, so a list inside a folder is found where it is expected, and offers a
 * new one at the top.
 */
@Composable
fun PlaylistPickerDialog(
    tree: PlaylistTree,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onPick: (Int, String) -> Unit,
) {
    val colors = SonorusTheme.colors
    SonorusDialog("Zu Playlist hinzufügen", onDismiss) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onNew)
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.List, null, tint = colors.accent)
                Text("Neue Playlist …", color = colors.accent, style = MaterialTheme.typography.bodyLarge)
            }

            for (folder in tree.folders) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp, start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Folder, null, tint = colors.textFaint, modifier = Modifier.size(14.dp))
                    RackLabelText(folder.name)
                }
                for (p in folder.playlists) PickRow(p.name, p.trackCount) { onPick(p.id, p.name) }
            }
            for (p in tree.loose) PickRow(p.name, p.trackCount) { onPick(p.id, p.name) }

            if (tree.folders.isEmpty() && tree.loose.isEmpty()) {
                Text(
                    "Noch keine Playlist.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textDim,
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PickRow(name: String, count: Int, onClick: () -> Unit) {
    val colors = SonorusTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.List, null, tint = colors.textDim, modifier = Modifier.size(18.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            Fmt.number(count),
            style = num(11.sp),
            color = colors.textFaint,
        )
    }
}
