package org.sonorus.data.download

import org.sonorus.data.model.Bootstrap
import org.sonorus.data.model.Track
import org.sonorus.data.model.User
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The index on disk.
 *
 * The promise it has to keep is narrow and absolute: **an entry in the index
 * means the file behind it can be played.** Everything here is a way for that
 * to be broken - a half-written index, a file that vanished, an app restarted -
 * and a check that it is not.
 */
class DownloadStoreTest {

    private lateinit var root: File
    private lateinit var store: DownloadStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("sonorus-store").toFile()
        store = DownloadStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun track(id: Int, title: String = "Song $id", codec: String = "") =
        Track(id = id, title = title, artist = "Bowie", codec = codec)

    /** Writes a file the way a finished download would have left one. */
    private fun store(id: Int, extension: String = "mp3", bytes: Int = 32): DownloadedTrack {
        val file = store.targetOf(id, extension)
        file.writeBytes(ByteArray(bytes))
        return DownloadedTrack(track = track(id), file = file.name, bytes = file.length())
    }

    @Test
    fun `what was downloaded is still there after a restart`() {
        store.put(store(1))
        store.rememberCover("/covers/album-7.jpg")
        File(store.coverDir, DownloadStore.coverName("/covers/album-7.jpg")).writeBytes(ByteArray(4))

        // A second store over the same directory is what a cold start is.
        val again = DownloadStore(root)

        assertTrue(again.isDownloaded(1))
        assertEquals(1, again.count)
        assertEquals(32L, again.bytes)
        assertEquals("1.mp3", again.fileOf(1)?.name)
        assertTrue(again.coverOf("/covers/album-7.jpg")?.isFile == true)
    }

    @Test
    fun `a song that was never downloaded has no file`() {
        assertFalse(store.isDownloaded(1))
        assertNull(store.fileOf(1))
        assertNull(store.coverOf("/covers/album-7.jpg"))
        assertNull(store.coverOf(null))
    }

    @Test
    fun `an entry whose file vanished does not survive the next start`() {
        store.put(store(1))
        store.put(store(2))
        store.targetOf(1, "mp3").delete()

        // What a phone that ran out of space, or storage that was cleared,
        // leaves behind - and exactly the lie this feature must not tell.
        assertNull(store.fileOf(1))
        assertEquals(1, store.prune())

        val again = DownloadStore(root)
        assertFalse(again.isDownloaded(1))
        assertTrue(again.isDownloaded(2))
    }

    @Test
    fun `removing a song takes its file and its playlist entry with it`() {
        store.put(store(1))
        store.put(store(2))
        store.rememberCollection(OfflineCollection(id = 5, name = "Abends", trackIds = listOf(1, 2)))

        store.remove(1)

        assertFalse(store.targetOf(1, "mp3").exists())
        assertTrue(store.targetOf(2, "mp3").exists())
        assertEquals(listOf(2), store.snapshot.playlists.single().trackIds)
    }

    @Test
    fun `a playlist that loses its last song loses its entry`() {
        store.put(store(1))
        store.rememberCollection(OfflineCollection(id = 5, name = "Abends", trackIds = listOf(1)))

        store.remove(1)

        assertTrue(store.snapshot.playlists.isEmpty())
    }

    @Test
    fun `downloading a song again replaces the old file rather than leaving two`() {
        store.put(store(1, extension = "mp3"))
        store.put(store(1, extension = "flac", bytes = 64))

        assertFalse(store.targetOf(1, "mp3").exists())
        assertEquals("1.flac", store.fileOf(1)?.name)
        assertEquals(1, store.count)
        assertEquals(64L, store.bytes)
    }

    @Test
    fun `clearing takes everything, files included`() {
        store.put(store(1))
        store.rememberAccount(Bootstrap(user = User(id = 1, username = "alex")))

        store.clear()

        assertEquals(0, store.count)
        assertNull(store.snapshot.account)
        assertTrue(store.audioDir.listFiles().orEmpty().isEmpty())
        assertTrue(DownloadStore(root).snapshot.tracks.isEmpty())
    }

    @Test
    fun `the last look at the account survives, or nothing could be drawn offline`() {
        store.rememberAccount(
            Bootstrap(user = User(id = 1, username = "alex", displayName = "Alex"), siteName = "Sonorus")
        )

        assertEquals("Alex", DownloadStore(root).snapshot.account?.user?.displayName)
    }

    @Test
    fun `an index that cannot be read starts empty instead of throwing`() {
        store.put(store(1))
        File(root, "library.json").writeText("{ this is not json")

        val again = DownloadStore(root)

        assertEquals(0, again.count)
        assertFalse(again.isDownloaded(1))
    }

    @Test
    fun `an index that does not name its version is not taken for the current one`() {
        // What a future migration depends on: a file written before the format
        // changed has to read as older, not as whatever the default now is.
        File(root, "library.json").writeText("""{"tracks":[{"track":{"id":1},"file":"1.mp3"}]}""")

        assertEquals(0, DownloadStore(root).count)
    }

    @Test
    fun `a cover path can never write outside the covers folder`() {
        assertEquals("covers_album-7.jpg", DownloadStore.coverName("/covers/album-7.jpg"))
        assertEquals(".._.._etc_passwd", DownloadStore.coverName("/../../etc/passwd"))
        assertFalse(DownloadStore.coverName("/covers/a b.jpg").contains('/'))
    }

    @Test
    fun `the file extension comes from the server, then from the codec, then a default`() {
        assertEquals("flac", DownloadStore.extensionFor(track(1, codec = "mp3"), "audio/flac"))
        // The header may carry parameters; they are not part of the type.
        assertEquals("flac", DownloadStore.extensionFor(track(1), "audio/flac; charset=binary"))
        assertEquals("mp3", DownloadStore.extensionFor(track(1, codec = "MP3"), null))
        assertEquals("audio", DownloadStore.extensionFor(track(1), null))
        assertEquals("audio", DownloadStore.extensionFor(track(1), "application/octet-stream"))
    }
}
