package org.sonorus.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.sonorus.data.download.OfflineCollection
import org.sonorus.data.model.SubtitleInfo

class VideoFormatTest {

    @Test
    fun `a subtitle track says under its language what kind it is`() {
        assertEquals("Englisch" to "Untertitel", VideoFmt.subtitleLabel(SubtitleInfo(lang = "eng", supported = true)))
        assertEquals("Englisch" to "Closed Captions", VideoFmt.subtitleLabel(SubtitleInfo(lang = "eng", sdh = true, supported = true)))
    }

    @Test
    fun `a file next to the video says so, a bitmap track keeps its note`() {
        assertEquals(
            "Englisch" to "Closed Captions · Datei",
            VideoFmt.subtitleLabel(SubtitleInfo(lang = "en", sdh = true, supported = true, external = true)),
        )
        assertEquals(
            "Deutsch · erzwungen" to "Bild-Untertitel, nicht unterstützt",
            VideoFmt.subtitleLabel(SubtitleInfo(lang = "ger", forced = true, supported = false)),
        )
    }

    @Test
    fun `a downloaded collection is named the way its page is`() {
        assertEquals("Album „Low“", holderLabel(OfflineCollection(kind = "album", id = 1, name = "Low")))
        assertEquals("Playlist „Abends“", holderLabel(OfflineCollection(kind = "playlist", id = 2, name = "Abends")))
        assertEquals("Bewertung „5 Sterne“", holderLabel(OfflineCollection(kind = "stars", id = 5, name = "5 Sterne")))
        assertEquals("Playlist", holderLabel(OfflineCollection(kind = "playlist", id = 3)))
    }
}
