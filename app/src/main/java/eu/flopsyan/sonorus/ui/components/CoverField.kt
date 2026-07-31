package eu.flopsyan.sonorus.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.flopsyan.sonorus.ui.theme.SonorusTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Picking, scaling and cropping a cover.
 *
 * Three rules carried over from the web app, and all three matter:
 *
 *  - **The picture is scaled down before it is sent.** A 23 MB photo would
 *    otherwise become a request of the same size, and nginx caps bodies at 1 MB
 *    by default - which is exactly what once broke the artist picture in
 *    production while it worked against localhost every time.
 *  - **A picture that is not square is dragged into its square**, and what gets
 *    stored is the crop, not the original. A stored offset would have to be
 *    applied in every grid, on the detail page *and* on the Media Session
 *    artwork - and the notification cannot take one. Trade-off worth knowing:
 *    the section cannot be nudged afterwards, reopening shows the stored square.
 *  - **The canvas is filled white first**, because JPEG has no transparency and
 *    a transparent PNG would otherwise come out black.
 */
object CoverImage {

    private const val MAX_EDGE = 1000
    private const val QUALITY = 85

    /** Decodes and scales down; small pictures keep their size. */
    suspend fun load(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            // Two passes: bounds first, so a huge photo is never fully decoded.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val longest = max(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return@runCatching null

            var sample = 1
            while (longest / (sample * 2) >= MAX_EDGE) sample *= 2

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return@runCatching null

            val edge = max(decoded.width, decoded.height)
            if (edge <= MAX_EDGE) return@runCatching decoded
            val scale = MAX_EDGE.toFloat() / edge
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).roundToInt().coerceAtLeast(1),
                (decoded.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        }.getOrNull()
    }

    /**
     * Cuts the square out. [fx] and [fy] say where it sits - 0 is the left/top
     * edge, 1 the right/bottom - and only the longer side has room to move, so
     * a wide picture goes left/right and a tall one up/down.
     */
    fun crop(source: Bitmap, fx: Float, fy: Float): Bitmap {
        val edge = minOf(source.width, source.height)
        val x = ((source.width - edge) * fx.coerceIn(0f, 1f)).roundToInt()
        val y = ((source.height - edge) * fy.coerceIn(0f, 1f)).roundToInt()

        val square = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        Canvas(square).apply {
            // JPEG has no transparency; without this a transparent PNG goes black.
            drawColor(Color.WHITE)
            drawBitmap(source, -x.toFloat(), -y.toFloat(), null)
        }
        return square
    }

    /** JPEG at 0.85, base64 without a data-URL prefix - what the server wants. */
    fun encode(bitmap: Bitmap): Pair<String, String> {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        return "image/jpeg" to Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

/** What the dialog holds while a cover is being picked. */
class CoverChoice {
    var source by mutableStateOf<Bitmap?>(null)
    var fx by mutableFloatStateOf(0.5f)
    var fy by mutableFloatStateOf(0.5f)
    /** True once the user asked for the picture to be removed. */
    var cleared by mutableStateOf(false)

    val hasNew: Boolean get() = source != null
    val changed: Boolean get() = hasNew || cleared

    /** The finished square, ready to be posted. */
    fun payload(): Pair<String, String>? =
        source?.let { CoverImage.encode(CoverImage.crop(it, fx, fy)) }
}

/**
 * The picker with its preview frame.
 *
 * **The frame is the preview, and that is the whole trick**: it shows exactly
 * the square the crop will cut, so dragging inside it needs no second
 * rendering path. The frame is deliberately large (152 dp), because the travel
 * of the drag *is* the hidden part of the picture at that size.
 */
@Composable
fun CoverField(
    choice: CoverChoice,
    currentUrl: String?,
    onPicked: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SonorusTheme.colors
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(onPicked)
    }

    val bitmap = choice.source
    // Only the longer side has room to move, so the hint names the direction.
    val movable = bitmap != null && bitmap.width != bitmap.height
    val horizontal = bitmap != null && bitmap.width > bitmap.height

    Column(modifier) {
        RackLabelText("Cover")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .size(152.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface2)
                    .then(
                        if (!movable || bitmap == null) Modifier else Modifier.pointerInput(bitmap) {
                            detectDragGestures { _, drag ->
                                // A drag across the frame is a drag across the
                                // hidden part - so the travel maps onto 0..1.
                                if (horizontal) {
                                    choice.fx = (choice.fx - drag.x / size.width).coerceIn(0f, 1f)
                                } else {
                                    choice.fy = (choice.fy - drag.y / size.height).coerceIn(0f, 1f)
                                }
                            }
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    bitmap != null -> Image(
                        bitmap = remember(bitmap, choice.fx, choice.fy) {
                            CoverImage.crop(bitmap, choice.fx, choice.fy).asImageBitmap()
                        },
                        contentDescription = "Vorschau",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    choice.cleared -> Text("Entfernt", color = colors.textFaint)
                    else -> Cover(currentUrl, Modifier.fillMaxSize(), RoundedCornerShape(10.dp))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SonorusButton("Bild wählen") { pick.launch("image/*") }
                if (currentUrl != null || choice.hasNew) {
                    SonorusButton("Entfernen", danger = true) {
                        choice.source = null
                        choice.cleared = true
                    }
                }
            }
        }
        if (movable) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (horizontal) "Zum Verschieben nach links oder rechts ziehen."
                else "Zum Verschieben nach oben oder unten ziehen.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textFaint,
            )
        }
    }
}
