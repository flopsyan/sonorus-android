package org.sonorus.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReservedTest {

    @Test
    fun `a look-alike is read back as the character it stands for`() {
        assertEquals("AC/DC", restoreReserved("AC\u2215DC"))
        assertEquals("125/Feuermond", restoreReserved("125\u2215Feuermond"))
        assertEquals("a/b\\c:d", restoreReserved("a\u29f8b\u29f9c\ua789d"))
    }

    @Test
    fun `a fullwidth form is read back in a latin name`() {
        assertEquals("40:1", restoreReserved("40\uff1a1"))
        assertEquals("What Do I Know?", restoreReserved("What Do I Know\uff1f"))
        assertEquals("Top of the \"M\"", restoreReserved("Top of the \uff02M\uff02"))
        assertEquals("B****", restoreReserved("B\uff0a\uff0a\uff0a\uff0a"))
        assertEquals("a|b<c>", restoreReserved("a\uff5cb\uff1cc\uff1e"))
    }

    @Test
    fun `a fullwidth form stays next to japanese`() {
        for (name in listOf(
            "\u306a\u3093\u3067\uff1f",
            "\u672c\u5f53\uff01\uff1f",
            "\uff1f\u672c\u5f53",
            "\u66f2\uff0a\uff0a\uff0a",
        )) assertEquals(name, restoreReserved(name))
        assertEquals("Title: \u65e5\u672c\u8a9e", restoreReserved("Title\uff1a \u65e5\u672c\u8a9e"))
        assertEquals("\u7d76\u5bfe/\u7d76\u547d", restoreReserved("\u7d76\u5bfe\u2215\u7d76\u547d"))
    }

    @Test
    fun `a character that only looks unusual is left alone`() {
        for (name in listOf(
            "\u00f7", "\u00d7", "Doki Doki \u2606 Morning", "Maybe I\u2019m Amazed",
            "Keep Dancing\u2026", "33\u2153", "\u201cquoted\u201d", "plain / : ? * \" < > |",
        )) assertEquals(name, restoreReserved(name))
    }
}
