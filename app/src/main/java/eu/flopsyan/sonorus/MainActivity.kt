package eu.flopsyan.sonorus

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import eu.flopsyan.sonorus.player.PlaybackService
import eu.flopsyan.sonorus.ui.AppPhase
import eu.flopsyan.sonorus.ui.AppViewModel
import eu.flopsyan.sonorus.ui.Shell
import eu.flopsyan.sonorus.ui.components.Loading
import eu.flopsyan.sonorus.ui.screens.LoginScreen
import eu.flopsyan.sonorus.ui.theme.SonorusTheme

@UnstableApi
class MainActivity : ComponentActivity() {

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Without this the media notification simply does not appear on
        // Android 13 and up - the service still runs, but nothing is drawn.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Binding a controller is what starts the MediaSessionService, which is
        // what puts playback in the foreground so it survives leaving the app.
        val token = SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))
        MediaController.Builder(this, token).buildAsync()
            .addListener({ }, MoreExecutors.directExecutor())

        setContent { SonorusRoot() }
    }
}

@UnstableApi
@Composable
private fun SonorusRoot() {
    val vm: AppViewModel = viewModel()
    val phase by vm.phase.collectAsState()
    val theme by vm.theme.collectAsState()

    SonorusTheme(mode = theme) {
        Box(Modifier.fillMaxSize().background(SonorusTheme.colors.bg)) {
            when (val current = phase) {
                is AppPhase.Starting -> Loading()
                is AppPhase.NeedsLogin -> LoginScreen(
                    initialServer = vm.api.serverUrl,
                    initialUser = SonorusApp.instance.session.username,
                    error = current.message,
                    busy = false,
                    onLogin = { server, user, pass -> vm.login(server, user, pass) { } },
                )
                is AppPhase.Ready -> Shell(vm, current.bootstrap)
            }
        }
    }
}
