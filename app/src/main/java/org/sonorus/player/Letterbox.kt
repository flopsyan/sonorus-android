package org.sonorus.player

import kotlin.math.max
import kotlin.math.min

/** How much of the frame's width and height is picture; 1 means no black bar on that axis. */
data class PictureShare(val width: Float, val height: Float)

/**
 * The black bars burnt into a video, learnt from small copies of its frames.
 *
 * The share only ever grows, so a dark scene can make it zoom less but never
 * cut into the picture once a brighter frame has been seen.
 */
class Letterbox {
    private var seen = 0
    private var width = 0f
    private var height = 0f

    /** Null until [MIN_FRAMES] frames said something. */
    val share: PictureShare?
        get() = if (seen < MIN_FRAMES) null else PictureShare(snap(width), snap(height))

    fun add(pixels: IntArray, w: Int, h: Int) {
        val frame = measure(pixels, w, h) ?: return
        seen++
        width = max(width, frame.width)
        height = max(height, frame.height)
    }

    companion object {
        const val MIN_FRAMES = 3

        // Encoded black sits near 0 in RGB; compression noise stays well below this.
        private const val DARK = 32
        // A frame that seems to be less than half picture is a dark scene, not bars.
        private const val MIN_SHARE = 0.5f

        private fun snap(share: Float) = if (share > 0.98f) 1f else share

        /** The picture's share of one frame, or null when the frame is too dark to tell. */
        fun measure(pixels: IntArray, w: Int, h: Int): PictureShare? {
            val rows = IntArray(h)
            val cols = IntArray(w)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val p = pixels[y * w + x]
                    val luma = (299 * (p shr 16 and 0xff) + 587 * (p shr 8 and 0xff) + 114 * (p and 0xff)) / 1000
                    if (luma > rows[y]) rows[y] = luma
                    if (luma > cols[x]) cols[x] = luma
                }
            }
            val top = rows.indexOfFirst { it > DARK }
            if (top < 0) return null
            val bottom = h - 1 - rows.indexOfLast { it > DARK }
            val left = cols.indexOfFirst { it > DARK }
            val right = w - 1 - cols.indexOfLast { it > DARK }
            // Bars are centred, so the thinner side is the bar; one row less for the scaled edge.
            val barY = max(0, min(top, bottom) - 1)
            val barX = max(0, min(left, right) - 1)
            val share = PictureShare((w - 2f * barX) / w, (h - 2f * barY) / h)
            return share.takeIf { it.width >= MIN_SHARE && it.height >= MIN_SHARE }
        }

        /**
         * How far to zoom a video fitted into [boxW] x [boxH] so its picture
         * touches the edges on one axis: never past that, so no picture is lost.
         */
        fun zoom(boxW: Float, boxH: Float, aspect: Float, share: PictureShare): Float {
            val fitW = min(boxW, boxH * aspect)
            val fitH = fitW / aspect
            return max(1f, min(boxW / (fitW * share.width), boxH / (fitH * share.height)))
        }
    }
}
