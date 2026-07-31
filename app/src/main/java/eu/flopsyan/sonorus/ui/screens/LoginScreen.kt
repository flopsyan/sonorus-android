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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eu.flopsyan.sonorus.ui.components.RackLabelText
import eu.flopsyan.sonorus.ui.components.SonorusButton
import eu.flopsyan.sonorus.ui.theme.SonorusTheme

/**
 * The first screen. The server address is a field rather than a build constant
 * so the app can be pointed at a test instance without being rebuilt.
 *
 * Worth knowing when a login fails: the session cookie is only sent back over
 * HTTPS, and Android blocks cleartext HTTP anyway - so a plain LAN IP will not
 * work, the domain will.
 */
@Composable
fun LoginScreen(
    initialServer: String,
    initialUser: String,
    error: String,
    busy: Boolean,
    onLogin: (String, String, String) -> Unit,
) {
    val colors = SonorusTheme.colors
    var server by remember { mutableStateOf(initialServer.ifEmpty { "https://sonorus.example.com" }) }
    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf("") }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LevelMeterMark()
            Spacer(Modifier.height(18.dp))
            Text("Sonorus", style = MaterialTheme.typography.displaySmall, color = colors.text)
            Spacer(Modifier.height(4.dp))
            Text(
                "Deine Musik, dein Server.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textDim,
            )
            Spacer(Modifier.height(28.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Field(
                    value = server,
                    onValueChange = { server = it },
                    label = "Server",
                    keyboardType = KeyboardType.Uri,
                )
                Field(
                    value = user,
                    onValueChange = { user = it },
                    label = "Benutzername",
                )
                Field(
                    value = pass,
                    onValueChange = { pass = it },
                    label = "Passwort",
                    password = true,
                    imeAction = ImeAction.Done,
                )

                if (error.isNotEmpty()) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.danger,
                    )
                }

                SonorusButton(
                    text = if (busy) "Wird angemeldet …" else "Anmelden",
                    primary = true,
                    enabled = !busy && server.isNotBlank() && user.isNotBlank() && pass.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onLogin(server.trim(), user.trim(), pass) },
                )
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
) {
    val colors = SonorusTheme.colors
    Column {
        RackLabelText(label)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.line,
                focusedTextColor = colors.text,
                unfocusedTextColor = colors.text,
                cursorColor = colors.accent,
                focusedContainerColor = colors.surface2,
                unfocusedContainerColor = colors.surface2,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The four bars of the app icon, as a mark over the login form. */
@Composable
private fun LevelMeterMark() {
    val colors = SonorusTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.accent)
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (h in listOf(14, 34, 22, 8)) {
            Box(
                Modifier
                    .width(6.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.accentInk)
            )
        }
    }
}
