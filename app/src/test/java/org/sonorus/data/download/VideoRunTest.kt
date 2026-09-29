package org.sonorus.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The season ring: how far a run over one season's episodes is. */
class VideoRunTest {

    private val season = listOf(1, 2, 3, 4)

    @Test
    fun `nothing queued of the season draws no ring`() {
        val state = VideoDownloads.State(done = setOf(1), runDone = setOf(1), queued = listOf(9), active = 8)
        assertNull(state.progressOf(season))
    }

    @Test
    fun `a first episode the server still prepares sweeps`() {
        val state = VideoDownloads.State(queued = listOf(2, 3), active = 1, phase = VideoDownloads.Phase.PREPARING, progress = 0.4f)
        assertNull(state.progressOf(season))
    }

    @Test
    fun `finished episodes and the running file's share count`() {
        val state = VideoDownloads.State(
            done = setOf(1, 2),
            runDone = setOf(1, 2),
            active = 3,
            queued = listOf(4),
            progress = 0.5f,
        )
        assertEquals(2.5f / 4, state.progressOf(season)!!, 0.0001f)
    }

    @Test
    fun `what was here before the run is not part of it`() {
        // Episode 1 was downloaded last week; the run is 2 to 4.
        val state = VideoDownloads.State(done = setOf(1, 2), runDone = setOf(2), active = 3, queued = listOf(4))
        assertEquals(1f / 3, state.progressOf(season)!!, 0.0001f)
    }

    @Test
    fun `another season in the same queue does not move this ring`() {
        val state = VideoDownloads.State(runDone = setOf(11, 12), active = 13, queued = listOf(1, 2), progress = 0.9f)
        assertNull(state.progressOf(season))
    }
}
