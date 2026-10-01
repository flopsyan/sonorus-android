package org.sonorus.ui.screens

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import kotlin.math.roundToInt
import androidx.media3.session.R as Media3R

enum class PipAction { BACK, TOGGLE, FORWARD }

private const val EXTRA_ACTION = "action"

/** Whether the app is shrunk into a picture-in-picture window right now. */
@Composable
fun rememberInPip(): Boolean {
    val activity = LocalContext.current.findActivity() as? ComponentActivity
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}

/**
 * Leaving the app while a video plays shrinks it into a window instead of pausing it.
 * The window gets the player's own buttons, or Android would show the music session's.
 */
@Composable
fun PipWhilePlaying(playing: Boolean, aspect: Float, source: Rect?, onAction: (PipAction) -> Unit) {
    val activity = LocalContext.current.findActivity() as? ComponentActivity ?: return
    if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
    val broadcast = activity.packageName + ".PIP"
    val currentAction by rememberUpdatedState(onAction)
    val currentPlaying by rememberUpdatedState(playing)

    fun params(): PictureInPictureParams {
        // Android refuses anything wider than 2.39:1 or narrower than 1:2.39.
        val ratio = aspect.coerceIn(0.42f, 2.39f)
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).roundToInt(), 1000))
            .setActions(pipActions(activity, broadcast, playing))
            .apply { source?.let { setSourceRectHint(it) } }
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(playing) }
            .build()
    }

    // Keyed, because the player recomposes with its clock several times a second.
    LaunchedEffect(playing, aspect, source) { runCatching { activity.setPictureInPictureParams(params()) } }

    DisposableEffect(activity) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                PipAction.entries.getOrNull(intent.getIntExtra(EXTRA_ACTION, -1))?.let { currentAction(it) }
            }
        }
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(broadcast), ContextCompat.RECEIVER_NOT_EXPORTED)
        // Before Android 12 there is no auto-enter, so going home is caught here. Empty
        // params, because Android adds them to the last set and these would be stale.
        val leave = Runnable {
            if (currentPlaying && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                runCatching { activity.enterPictureInPictureMode(PictureInPictureParams.Builder().build()) }
            }
        }
        activity.addOnUserLeaveHintListener(leave)
        onDispose {
            activity.removeOnUserLeaveHintListener(leave)
            activity.unregisterReceiver(receiver)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()) }
            }
        }
    }
}

private fun pipActions(context: Context, broadcast: String, playing: Boolean): List<RemoteAction> {
    fun action(which: PipAction, icon: Int, title: String): RemoteAction {
        val intent = Intent(broadcast).setPackage(context.packageName).putExtra(EXTRA_ACTION, which.ordinal)
        val pending = PendingIntent.getBroadcast(context, which.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return RemoteAction(Icon.createWithResource(context, icon), title, title, pending)
    }
    return listOf(
        action(PipAction.BACK, Media3R.drawable.media3_icon_skip_back_10, "10 Sekunden zurück"),
        if (playing) action(PipAction.TOGGLE, Media3R.drawable.media3_icon_pause, "Pause")
        else action(PipAction.TOGGLE, Media3R.drawable.media3_icon_play, "Abspielen"),
        action(PipAction.FORWARD, Media3R.drawable.media3_icon_skip_forward_10, "10 Sekunden vor"),
    )
}
