package org.sonorus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.sonorus.ui.components.RackLabelText
import org.sonorus.ui.components.SonorusButton
import org.sonorus.ui.theme.SonorusTheme

/**
 * Logged in, no server, and nothing on the phone to fall back on.
 *
 * **This screen exists so that the login screen never has to be shown for this.**
 * It used to be: a cold start out of coverage offered the login form, which
 * reads as "you have been logged out" to anybody holding the phone - on a ferry
 * with no way to prove otherwise, and with the downloads it was bought for
 * apparently gone. The session is still perfectly good; what is missing is a
 * network, and no password fixes that.
 *
 * So the page says the one true thing and offers the one action that can help.
 * Signing out is down at the bottom, quiet and deliberate: it is the way to move
 * the app to another server, and it is the only thing here that throws anything
 * away.
 */
@Composable
fun OfflineScreen(
    message: String,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = SonorusTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            tint = colors.textFaint,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(20.dp))
        RackLabelText("Keine Verbindung")
        Spacer(Modifier.height(10.dp))
        Text(
            "Sonorus ist gerade nicht erreichbar, und auf diesem Gerät liegt noch " +
                "keine Musik. Sobald es wieder Verbindung gibt, ist alles wie vorher - " +
                "du bist weiterhin angemeldet.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        if (message.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SonorusButton("Erneut versuchen", primary = true, onClick = onRetry)
        }
        Spacer(Modifier.height(36.dp))
        Text(
            "Willst du zu einem anderen Server?",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textFaint,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        SonorusButton("Abmelden", danger = true, onClick = onSignOut)
    }
}
