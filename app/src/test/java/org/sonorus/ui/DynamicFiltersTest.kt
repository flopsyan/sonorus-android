package org.sonorus.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonorus.data.model.DynamicOptions
import org.sonorus.data.model.DynamicRules
import org.sonorus.data.model.FilterOption

/** The phone has to tick, name and send the filters exactly as the web page does. */
class DynamicFiltersTest {

    private val options = DynamicOptions(
        genres = listOf(FilterOption(1, "Rock"), FilterOption(2, "Pop"), FilterOption(3, "Jazz"), FilterOption(4, "Soul")),
        artists = listOf(FilterOption(10, "ABBA"), FilterOption(11, "Queen"), FilterOption(12, "Muse"), FilterOption(13, "Toto")),
        albums = listOf(FilterOption(20, "Ten")),
        decades = listOf(FilterOption(2000, "2000er"), FilterOption(1990, "1990er"), FilterOption(1980, "1980er")),
        stars = listOf(5, 4, 3, 2, 1, 0).map { FilterOption(it, starLabel(it)) },
    )

    @Test
    fun `no rules means everything is ticked and nothing filters`() {
        val state = FilterState.from(null, options)
        assertTrue(FilterKind.entries.all { state.isAll(it, options) })
        assertEquals(0, state.activeCount(options))
        assertEquals("alle", state.summary(FilterKind.ARTISTS, options).first)
    }

    @Test
    fun `the server's all and id lists are read back`() {
        val rules = DynamicRules(
            genres = JsonArray(listOf(JsonPrimitive(1))),
            artists = JsonPrimitive("all"),
            ranges = listOf(listOf(1990, 1994)),
            decades = JsonArray(emptyList()),
        )
        val state = FilterState.from(rules, options)
        assertEquals(setOf(1), state.ticked(FilterKind.GENRES))
        assertTrue(state.isAll(FilterKind.ARTISTS, options))
        assertEquals("1990-1994", state.summary(FilterKind.DECADES, options).first)
        assertEquals(2, state.activeCount(options))
    }

    @Test
    fun `summaries say what the web page says`() {
        var state = FilterState.from(null, options).toggle(FilterKind.ARTISTS, 11)
        assertEquals("ohne Queen" to true, state.summary(FilterKind.ARTISTS, options))
        state = state.with(FilterKind.GENRES, setOf(1, 2))
        assertEquals("Rock, Pop", state.summary(FilterKind.GENRES, options).first)
        state = state.with(FilterKind.GENRES, emptySet())
        assertEquals("keine", state.summary(FilterKind.GENRES, options).first)
        state = state.with(FilterKind.STARS, setOf(5, 4, 0))
        assertEquals("4-5 Sterne, unbewertet", state.summary(FilterKind.STARS, options).first)
    }

    @Test
    fun `a range over every decade replaces them`() {
        val state = FilterState.from(null, options).addRange(2012, 2008, options)
        assertTrue(state.ticked(FilterKind.DECADES).isEmpty())
        assertEquals(listOf(listOf(2008, 2012)), state.ranges)
        val more = state.toggle(FilterKind.DECADES, 1980).addRange(2000, 2004, options)
        assertEquals(setOf(1980), more.ticked(FilterKind.DECADES))
        assertEquals(2, more.ranges.size)
    }

    @Test
    fun `the payload sends all or the ticked ids`() {
        val state = FilterState.from(null, options).toggle(FilterKind.ARTISTS, 11)
        val body = state.payload(options)
        assertEquals("all", body["genres"]!!.jsonPrimitive.content)
        assertEquals(listOf(10, 12, 13), body["artists"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertFalse(body["ranges"]!!.jsonArray.isNotEmpty())
    }
}
