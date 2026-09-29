package org.sonorus.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/** A stretch of cue text and the styles over it. */
data class CueSpan(
    val text: String,
    val italic: Boolean = false,
    val bold: Boolean = false,
    val underline: Boolean = false,
)

/**
 * The markup the server lets through in a cue: `<i>`, `<b>` and `<u>`, as the
 * web player renders them. Any other tag is dropped.
 */
object CueMarkup {

    private val TAG = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9]*)(\s[^>]*)?>""")

    /** An unclosed tag simply ends with the cue, which is why each cue is parsed on its own. */
    fun parse(text: String): List<CueSpan> {
        val out = mutableListOf<CueSpan>()
        // Counted rather than switched, so `<i>a <i>b</i> c</i>` stays italic to the end.
        val open = mutableMapOf("i" to 0, "b" to 0, "u" to 0)
        var at = 0
        fun emit(until: Int) {
            if (until <= at) return
            val span = CueSpan(text.substring(at, until), open.getValue("i") > 0, open.getValue("b") > 0, open.getValue("u") > 0)
            val last = out.lastOrNull()
            if (last != null && last.copy(text = span.text) == span) {
                out[out.lastIndex] = last.copy(text = last.text + span.text)
            } else {
                out += span
            }
        }
        for (m in TAG.findAll(text)) {
            emit(m.range.first)
            at = m.range.last + 1
            val name = m.groupValues[2].lowercase()
            val count = open[name] ?: continue
            open[name] = if (m.groupValues[1] == "/") (count - 1).coerceAtLeast(0) else count + 1
        }
        emit(text.length)
        return out
    }

    /** Several cues on screen at once, one per line. */
    fun annotated(cues: List<String>): AnnotatedString = buildAnnotatedString {
        cues.forEachIndexed { index, cue ->
            if (index > 0) append("\n")
            for (span in parse(cue)) {
                val style = SpanStyle(
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    textDecoration = if (span.underline) TextDecoration.Underline else null,
                )
                if (style == SpanStyle()) append(span.text) else withStyle(style) { append(span.text) }
            }
        }
    }
}
