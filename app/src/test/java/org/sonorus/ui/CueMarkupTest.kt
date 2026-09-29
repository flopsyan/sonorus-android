package org.sonorus.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CueMarkupTest {

    @Test
    fun `plain text is one plain span`() {
        assertEquals(listOf(CueSpan("He'll stop at nothing")), CueMarkup.parse("He'll stop at nothing"))
    }

    @Test
    fun `an italic cue loses its tags and keeps its style`() {
        assertEquals(
            listOf(CueSpan("He'll stop at nothing to make sure", italic = true)),
            CueMarkup.parse("<i>He'll stop at nothing to make sure</i>"),
        )
    }

    @Test
    fun `tags are read in any case`() {
        assertEquals(
            listOf(CueSpan("a", bold = true), CueSpan(" b", underline = true)),
            CueMarkup.parse("<B>a</b><U> b</U>"),
        )
    }

    @Test
    fun `nested tags add up and close one at a time`() {
        assertEquals(
            listOf(
                CueSpan("a ", italic = true),
                CueSpan("b", italic = true, bold = true),
                CueSpan(" c", italic = true),
                CueSpan(" d"),
            ),
            CueMarkup.parse("<i>a <b>b</b> c</i> d"),
        )
    }

    @Test
    fun `the same tag twice stays on until both are closed`() {
        assertEquals(
            listOf(CueSpan("a b c", italic = true), CueSpan(" d")),
            CueMarkup.parse("<i>a <i>b</i> c</i> d"),
        )
    }

    @Test
    fun `an unclosed tag runs to the end of the cue`() {
        assertEquals(
            listOf(CueSpan("- Go.\n"), CueSpan("- Now!", italic = true)),
            CueMarkup.parse("- Go.\n<i>- Now!"),
        )
    }

    @Test
    fun `a stray closing tag changes nothing`() {
        assertEquals(listOf(CueSpan("a b")), CueMarkup.parse("a</i> b"))
    }

    @Test
    fun `any other tag is dropped, its text kept`() {
        assertEquals(
            listOf(CueSpan("red ", italic = false), CueSpan("and italic", italic = true)),
            CueMarkup.parse("<font color=\"#ff0000\">red </font><i>and italic</i>"),
        )
    }

    @Test
    fun `a lone angle bracket is text`() {
        assertEquals(listOf(CueSpan("3 < 4 > 2")), CueMarkup.parse("3 < 4 > 2"))
    }

    @Test
    fun `an unclosed tag of one cue does not leak into the next`() {
        val text = CueMarkup.annotated(listOf("<i>first", "second"))
        assertEquals("first\nsecond", text.text)
        assertEquals(1, text.spanStyles.size)
        assertEquals(0, text.spanStyles[0].start)
        assertEquals(5, text.spanStyles[0].end)
    }
}
