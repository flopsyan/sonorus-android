package org.sonorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonorus.data.model.LyricsResponse

/**
 * `GET /api/tracks/:id/lyrics` and the answer of `POST .../lyrics-review`, both taken
 * off a running instance. `reviewable` decides whether the verdict button shows, and
 * a server from before it must read as "no button" rather than fail to decode.
 */
class LyricsWireTest {

    @Test
    fun `words from an unjudged lrc can be judged`() {
        val body = """
            {"ok":true,"lyrics":{"text":"Epsilon lrc one\nEpsilon lrc two",
             "lines":[{"time":1,"text":"Epsilon lrc one"},{"time":3,"text":"Epsilon lrc two"}],
             "synced":true,"offset":0,"reviewable":true}}
        """
        val lyrics = ApiJson.decodeFromString<LyricsResponse>(body).lyrics
        assertTrue(lyrics.reviewable)
        assertEquals(2, lyrics.lines.size)
    }

    @Test
    fun `a rejected lrc answers with the file's own words`() {
        val body = """
            {"ok":true,"lyrics":{"text":"Beta from the tag\nsecond tag line","lines":[],
             "synced":false,"offset":0,"reviewable":false}}
        """
        val lyrics = ApiJson.decodeFromString<LyricsResponse>(body).lyrics
        assertFalse(lyrics.reviewable)
        assertEquals("Beta from the tag\nsecond tag line", lyrics.text)
    }

    @Test
    fun `a server without verdicts shows no button`() {
        val body = """{"ok":true,"lyrics":{"text":"a","lines":[],"synced":false,"offset":0}}"""
        assertFalse(ApiJson.decodeFromString<LyricsResponse>(body).lyrics.reviewable)
    }
}
