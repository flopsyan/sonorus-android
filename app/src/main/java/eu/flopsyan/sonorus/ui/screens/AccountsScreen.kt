package eu.flopsyan.sonorus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import eu.flopsyan.sonorus.data.model.User
import eu.flopsyan.sonorus.ui.AppViewModel
import eu.flopsyan.sonorus.ui.LoadBox
import eu.flopsyan.sonorus.ui.components.ConfirmDialog
import eu.flopsyan.sonorus.ui.components.RackLabelText
import eu.flopsyan.sonorus.ui.components.SonorusButton
import eu.flopsyan.sonorus.ui.components.SonorusDialog
import eu.flopsyan.sonorus.ui.rememberLoad
import eu.flopsyan.sonorus.ui.theme.SonorusTheme
import kotlinx.coroutines.launch
import eu.flopsyan.sonorus.ui.components.ServerOnlyNote
import eu.flopsyan.sonorus.ui.LocalOffline

/**
 * The account list.
 *
 * Deliberately visible to everyone - `GET /api/users` is not admin-gated, so
 * every logged-in user sees who has an account. Only creating and deleting are
 * admin-only, and the gate that matters is the server's; hiding the buttons
 * here is just courtesy.
 *
 * Note the flag is set at creation time only: no endpoint promotes an existing
 * account, so there is nothing here to toggle either.
 */
@UnstableApi
@Composable
fun AccountsScreen(vm: AppViewModel) {
    if (LocalOffline.current) return ServerOnlyNote("Die Konten")
    val colors = SonorusTheme.colors
    val scope = rememberCoroutineScope()
    val isAdmin = vm.bootstrap?.user?.isAdmin == true
    val me = vm.bootstrap?.user?.id
    var users by remember { mutableStateOf<List<User>?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<User?>(null) }

    val load = rememberLoad("users") { vm.api.users().users }
    val list = users ?: load.value

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        if (isAdmin) {
            Box(Modifier.padding(16.dp)) {
                SonorusButton("Konto anlegen", primary = true) { creating = true }
            }
        }
        LoadBox(load) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                for (user in list.orEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.surface)
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(colors.surface3),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                user.avatar.ifEmpty {
                                    user.displayName.ifEmpty { user.username }.take(1).uppercase()
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textDim,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                user.displayName.ifEmpty { user.username },
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.text,
                            )
                            Text(
                                "@${user.username}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textDim,
                            )
                        }
                        if (user.isAdmin) {
                            Text(
                                "Admin",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.accent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(colors.accentSoft)
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                        // The server refuses the last account and the last
                        // admin, so an instance can never lock itself out.
                        if (isAdmin && user.id != me) {
                            IconButton(onClick = { deleting = user }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Filled.Delete, "Konto löschen",
                                    tint = colors.danger, modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        NewAccountDialog(
            onDismiss = { creating = false },
            onCreate = { name, pass, display, admin ->
                creating = false
                scope.launch {
                    runCatching { vm.api.createUser(name, pass, display, admin) }
                        .onSuccess {
                            users = it.users
                            vm.say("Konto \"$name\" angelegt.")
                        }
                        .onFailure { vm.say(vm.message(it), true) }
                }
            },
        )
    }
    deleting?.let { user ->
        ConfirmDialog(
            title = "Konto löschen",
            message = "\"${user.displayName.ifEmpty { user.username }}\" wird endgültig gelöscht, " +
                "mit allen Playlists, Bewertungen und dem Verlauf. Das lässt sich nicht rückgängig machen.",
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                scope.launch {
                    runCatching { vm.api.deleteUser(user.id) }
                        .onSuccess {
                            users = it.users
                            vm.say("Konto gelöscht.")
                        }
                        .onFailure { vm.say(vm.message(it), true) }
                }
            },
        )
    }
}

@Composable
private fun NewAccountDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, Boolean) -> Unit,
) {
    val colors = SonorusTheme.colors
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var display by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf(false) }

    SonorusDialog(
        title = "Konto anlegen",
        onDismiss = onDismiss,
        confirmLabel = "Anlegen",
        confirmEnabled = username.isNotBlank() && password.length >= 4,
        onConfirm = { onCreate(username.trim(), password, display.trim().ifEmpty { username.trim() }, admin) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DialogField("Benutzername", username) { username = it }
            DialogField("Anzeigename", display) { display = it }
            DialogField("Passwort", password, password = true) { password = it }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Checkbox(
                    checked = admin,
                    onCheckedChange = { admin = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = colors.accent,
                        checkmarkColor = colors.accentInk,
                        uncheckedColor = colors.line,
                    ),
                )
                Column {
                    Text("Administrator", style = MaterialTheme.typography.bodyMedium, color = colors.text)
                    // Worth saying, because it cannot be changed afterwards.
                    Text(
                        "Darf Konten anlegen und löschen. Nur jetzt setzbar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textFaint,
                    )
                }
            }
        }
    }
}

@Composable
fun DialogField(
    label: String,
    value: String,
    password: Boolean = false,
    placeholder: String = "",
    onValueChange: (String) -> Unit,
) {
    val colors = SonorusTheme.colors
    Column {
        RackLabelText(label)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = if (placeholder.isEmpty()) null else {
                { Text(placeholder, color = colors.textFaint) }
            },
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
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
