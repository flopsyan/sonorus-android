package org.sonorus.ui.screens

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.sonorus.ui.components.RackLabelText
import org.sonorus.ui.components.SonorusButton
import org.sonorus.ui.theme.SonorusTheme

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
    var server by remember { mutableStateOf(initialServer) }
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
                    placeholder = "https://musik.example.com",
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

/** How long the letter just typed stays readable before it is masked. */
private const val REVEAL_MS = 1200L

/**
 * A password mask that leaves the character just typed readable.
 *
 * Compose's own [androidx.compose.ui.text.input.PasswordVisualTransformation]
 * hides everything the instant it arrives, which is not what an Android
 * password field does and not what a phone keyboard needs: typing a long
 * password blind is how a login fails three times over one mistyped letter.
 *
 * The mapping stays the identity because the length never changes - one glyph
 * out, one glyph in.
 */
private class RevealLastTransformation(private val reveal: Boolean) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val shown = if (reveal && text.isNotEmpty()) {
            DOT.repeat(text.length - 1) + text.text.last()
        } else {
            DOT.repeat(text.length)
        }
        return TransformedText(AnnotatedString(shown), OffsetMapping.Identity)
    }

    private companion object {
        const val DOT = "\u2022"
    }
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
) {
    val colors = SonorusTheme.colors
    // The last letter is readable for a moment after it is typed, and only
    // after typing: revealing it again on a backspace would show a character
    // nobody asked to see. LaunchedEffect(value) restarts with every keystroke,
    // so holding a key down never leaves a letter standing.
    var reveal by remember { mutableStateOf(false) }
    var previous by remember { mutableStateOf(value) }
    if (password) {
        LaunchedEffect(value) {
            val grew = value.length > previous.length
            previous = value
            if (!grew) {
                reveal = false
                return@LaunchedEffect
            }
            reveal = true
            delay(REVEAL_MS)
            reveal = false
        }
    }
    Column {
        RackLabelText(label)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = if (placeholder.isEmpty()) null else {
                { Text(placeholder, color = colors.textDim) }
            },
            singleLine = true,
            visualTransformation = if (password) RevealLastTransformation(reveal) else VisualTransformation.None,
            // KeyboardType.Password is what switches the keyboard's own
            // autocorrect, its word list and the suggestion strip off - a
            // masked field on a plain text keyboard still learns the password
            // and still offers it back as a suggestion. `autoCorrectEnabled`
            // says the same thing to keyboards that ignore the type.
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = if (password) false else null,
            ),
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
