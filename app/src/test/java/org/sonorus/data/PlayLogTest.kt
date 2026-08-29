package org.sonorus.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant

/**
 * The plays that were heard with no server to tell.
 *
 * The promise is narrow and absolute: **a play heard offline is not lost, and
 * it does not move in time.** Everything here is a way for that to be broken -
 * the app killed, the server still gone, a track deleted while the phone was
 * away - and a check that it is not.
 *
 * Two notes on driving it, both learned the hard way:
 *  - the log is given `backgroundScope`, because its collector never returns and
 *    `runTest` would otherwise fail the test for leaving a coroutine running;
 *  - a new value is delivered by **`runCurrent()`**, not by `advanceUntilIdle()`
 *    - the latter leaves the background collector suspended and the test then
 *    sees nothing sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayLogTest {

    private lateinit var root: File
    private lateinit var file: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("sonorus-plays").toFile()
        file = File(root, "plays.json")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** One play as the server saw it arrive. */
    private data class Sent(val trackId: Int, val seconds: Double, val playedAt: String)

    @Test
    fun `a play written while offline survives the process`() = runTest {
        PlayLog(file, MutableStateFlow(true), { _, _, _ -> }, backgroundScope)
            .record(trackId = 7, seconds = 120)
        assertTrue("written straight away, not on the way out", file.exists())

        // A second instance is what a restarted app sees.
        val reopened = PlayLog(file, MutableStateFlow(true), { _, _, _ -> }, backgroundScope)
        assertEquals(1, reopened.waiting.value)
    }

    @Test
    fun `coming back online sends what is waiting and empties the file`() = runTest {
        val offline = MutableStateFlow(true)
        val sent = mutableListOf<Sent>()
        val log = PlayLog(file, offline, { t, s, at -> sent += Sent(t, s, at) }, backgroundScope)
        log.record(trackId = 7, seconds = 120)
        log.record(trackId = 9, seconds = 60)
        testScheduler.runCurrent()
        assertEquals(2, log.waiting.value)
        assertTrue("nothing goes out while offline", sent.isEmpty())

        offline.value = false
        testScheduler.runCurrent()

        assertEquals("oldest first", listOf(7, 9), sent.map { it.trackId })
        assertEquals(120.0, sent[0].seconds, 0.0)
        assertEquals(0, log.waiting.value)
    }

    @Test
    fun `a play carries the moment it was heard, not the moment it was sent`() = runTest {
        val offline = MutableStateFlow(true)
        val sent = mutableListOf<Sent>()
        val log = PlayLog(file, offline, { t, s, at -> sent += Sent(t, s, at) }, backgroundScope)
        val before = Instant.now()
        log.record(trackId = 7, seconds = 120)
        val after = Instant.now()

        offline.value = false
        testScheduler.runCurrent()

        val stamped = Instant.parse(sent.single().playedAt)
        assertFalse("not before it happened", stamped.isBefore(before.minusSeconds(1)))
        assertFalse("not after it happened", stamped.isAfter(after.plusSeconds(1)))
    }

    @Test
    fun `a server that still refuses leaves the plays on the phone`() = runTest {
        val offline = MutableStateFlow(true)
        val log = PlayLog(file, offline, { _, _, _ -> throw IOException("no route") }, backgroundScope)
        log.record(trackId = 7, seconds = 120)

        offline.value = false
        testScheduler.runCurrent()

        assertEquals("still waiting, not eaten", 1, log.waiting.value)
    }

    @Test
    fun `a track the server no longer knows is dropped instead of jamming the queue`() = runTest {
        val offline = MutableStateFlow(true)
        val sent = mutableListOf<Int>()
        val log = PlayLog(
            file,
            offline,
            { t, _, _ -> if (t == 7) throw ApiException("not_found", "weg") else sent += t },
            backgroundScope,
        )
        log.record(trackId = 7, seconds = 120)
        log.record(trackId = 9, seconds = 60)

        offline.value = false
        testScheduler.runCurrent()

        assertEquals("the live one still got through", listOf(9), sent)
        assertEquals(0, log.waiting.value)
    }

    @Test
    fun `correcting the seconds of a running play does not move its timestamp`() = runTest {
        val offline = MutableStateFlow(true)
        val sent = mutableListOf<Sent>()
        val log = PlayLog(file, offline, { t, s, at -> sent += Sent(t, s, at) }, backgroundScope)
        val id = log.record(trackId = 7, seconds = 30)
        val stampedAt = Instant.now()
        log.update(id, 240)
        log.update(id, 100) // never backwards

        offline.value = false
        testScheduler.runCurrent()

        assertEquals(240.0, sent.single().seconds, 0.0)
        assertFalse(
            "the timestamp belongs to the start of the play",
            Instant.parse(sent.single().playedAt).isAfter(stampedAt.plusSeconds(1)),
        )
    }
}
