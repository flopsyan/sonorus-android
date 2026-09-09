package org.sonorus.data

/**
 * The faces a book can be set in.
 *
 * Ubuntu is the one Florian asked for and the only one that is **not** a
 * question of what the phone happens to have: it is served by Sonorus as four
 * `@font-face` files, so it is there whether or not it is installed. The other
 * three name the phone's own generic families, which is what a reader is used
 * to being offered.
 */
enum class ReaderFont(val wire: String, val label: String, val css: String) {
    UBUNTU("ubuntu", "Ubuntu", "'Ubuntu', system-ui, sans-serif"),
    SERIF("serif", "Serif", "Georgia, 'Times New Roman', serif"),
    SANS("sans", "Sans", "system-ui, 'Roboto', sans-serif"),
    MONO("mono", "Mono", "ui-monospace, monospace");

    companion object {
        fun of(wire: String?): ReaderFont =
            entries.firstOrNull { it.wire == wire } ?: UBUNTU
    }
}

/**
 * How the reading view is set on this phone.
 *
 * [size] is the text size in CSS pixels, [leading] the line height as a
 * multiple of it, [margin] the space left and right in pixels. The vertical
 * padding follows the horizontal one rather than being its own setting - two
 * numbers for what reads as one margin is a setting nobody wants.
 */
data class ReaderStyle(
    val font: ReaderFont = ReaderFont.UBUNTU,
    val size: Int = 18,
    val leading: Float = 1.6f,
    val margin: Int = 22,
) {
    fun withSize(value: Int) = copy(size = value.coerceIn(MIN_SIZE, MAX_SIZE))

    fun withLeading(value: Float) = copy(leading = value.coerceIn(MIN_LEADING, MAX_LEADING))

    fun withMargin(value: Int) = copy(margin = value.coerceIn(MIN_MARGIN, MAX_MARGIN))

    /** The shape `Reader.style()` in the server's reader.js expects. */
    fun css(ink: String, bg: String, dim: String, accent: String): Map<String, String> = mapOf(
        "ink" to ink,
        "bg" to bg,
        "dim" to dim,
        "accent" to accent,
        "font" to font.css,
        "size" to "${size}px",
        "leading" to leading.toString(),
        "padH" to "${margin}px",
        // A page wants more air above and below than beside it, or the first
        // line sits against the status bar.
        "padV" to "${margin + 6}px",
    )

    companion object {
        val DEFAULT = ReaderStyle()
        const val MIN_SIZE = 12
        const val MAX_SIZE = 30
        const val MIN_LEADING = 1.2f
        const val MAX_LEADING = 2.2f
        const val MIN_MARGIN = 8
        const val MAX_MARGIN = 48
    }
}
