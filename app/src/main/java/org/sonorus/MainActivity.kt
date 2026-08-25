package org.sonorus

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import org.sonorus.player.PlaybackService
import org.sonorus.ui.AppPhase
import org.sonorus.ui.AppViewModel
import org.sonorus.ui.LocalOffline
import org.sonorus.ui.Shell
import org.sonorus.ui.components.Loading
import org.sonorus.ui.screens.LoginScreen
import org.sonorus.ui.screens.OfflineScreen
import org.sonorus.ui.screens.MapsMode
import org.sonorus.ui.screens.isCompactWindow
import org.sonorus.ui.theme.SonorusTheme

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

    /**
     * Leaving the app is the other moment the queue is worth writing down. While
     * something plays the ticker does it every few seconds, but a player that
     * was paused and then left would otherwise keep a position that is up to
     * that much too old.
     */
    override fun onStop() {
        super.onStop()
        SonorusApp.instance.player.saveQueue()
    }
}

@UnstableApi
@Composable
private fun SonorusRoot() {
    val vm: AppViewModel = viewModel()
    val phase by vm.phase.collectAsState()
    val theme by vm.theme.collectAsState()
    val offline by vm.offline.collectAsState()

    // Kept across the swap below, so squeezing the window and pulling it open
    // again lands back on the page that was open. `rememberNavController` saves
    // its back stack, but only while something holds that state for it - and the
    // shell leaves the composition entirely when the strip takes over.
    val branches = rememberSaveableStateHolder()

    // Which source answers is a fact about the whole tree, not about one screen.
    CompositionLocalProvider(LocalOffline provides offline) {
        SonorusTheme(mode = theme) {
            // The height the app really has, measured rather than read off the
            // Configuration - see [isCompactWindow]. It is taken here at the
            // root, because this is the one box that is the whole window.
            BoxWithConstraints(Modifier.fillMaxSize().background(SonorusTheme.colors.bg)) {
                val compact = isCompactWindow(maxHeight)
                when (val current = phase) {
                    is AppPhase.Starting -> Loading()
                    is AppPhase.NeedsLogin -> LoginScreen(
                        initialServer = vm.api.serverUrl,
                        initialUser = SonorusApp.instance.session.username,
                        error = current.message,
                        busy = false,
                        onLogin = { server, user, pass -> vm.login(server, user, pass) { } },
                    )
                    // Logged in, no server, nothing downloaded. Deliberately not
                    // the login form: nothing is wrong with the login, and asking
                    // for a password that was never rejected reads as having been
                    // thrown out. See [AppPhase.OfflineEmpty].
                    is AppPhase.OfflineEmpty -> OfflineScreen(
                        message = current.message,
                        onRetry = { vm.retryConnection() },
                        onSignOut = { vm.logout() },
                    )
                    // A window too short for the shell gets the transport and
                    // nothing else - see [MapsMode]. Deliberately only from
                    // Ready: there is nothing to play before that, and a server
                    // address cannot be typed into a strip.
                    is AppPhase.Ready ->
                        if (compact) {
                            branches.SaveableStateProvider("compact") { MapsMode(vm) }
                        } else {
                            branches.SaveableStateProvider("shell") { Shell(vm, current.bootstrap) }
                        }
                }
            }
        }
    }
}
