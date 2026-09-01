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
    fun `removing a song takes its file, and leaves the playlist's own list alone`() {
        store.put(store(1))
        store.put(store(2))
        store.rememberCollection(OfflineCollection(id = 5, name = "Abends", trackIds = listOf(1, 2)))

        store.remove(1)

        assertFalse(store.targetOf(1, "mp3").exists())
        assertTrue(store.targetOf(2, "mp3").exists())
        // The list is what the *server* said the playlist holds, and it is the
        // baseline the next reconcile diffs against. Editing it from this side
        // would read as "the server dropped this song" and delete it a second
        // time - or fetch it back, because the server still has it. Deleting a
        // download on purpose is recorded as an exclusion instead.
        assertEquals(listOf(1, 2), store.snapshot.playlists.single().trackIds)
    }

    @Test
    fun `a playlist that loses its last song loses its entry`() {
        store.put(store(1))
        store.rememberCollection(OfflineCollection(id = 5, name = "Abends", trackIds = listOf(1)))

        store.remove(1)

        assertTrue(store.snapshot.playlists.isEmpty())
    }

    // --- Who still wants a song, and who deleted one on purpose ---------------

    @Test
    fun `a song is held by every collection that lists it, and by a hand download`() {
        store.rememberCollection(OfflineCollection(kind = "playlist", id = 5, trackIds = listOf(1, 2)))
        store.rememberCollection(OfflineCollection(kind = "album", id = 9, trackIds = listOf(2, 3)))
        store.rememberManual(listOf(4))

        val playlist = OfflineCollection(kind = "playlist", id = 5).key
        // Song 2 is in the album as well: the playlist letting go is not enough.
        assertTrue(store.isHeld(2, exceptKey = playlist))
        // Song 1 is only in this playlist, so it really is going.
        assertFalse(store.isHeld(1, exceptKey = playlist))
        // And one somebody fetched on its own is held by that alone.
        assertTrue(store.isHeld(4, exceptKey = playlist))
        assertEquals(setOf(2, 3, 4), store.heldBy(exceptKey = playlist))
    }

    @Test
    fun `a genre selection and a single genre are different collections`() {
        store.rememberCollection(OfflineCollection(kind = "genre", id = 3, ids = listOf(3), trackIds = listOf(1)))
        store.rememberCollection(
            OfflineCollection(kind = "genre", id = 3, ids = listOf(3, 7), trackIds = listOf(2))
        )

        // Matched on the whole selection, or `/genres/3,7` would overwrite the
        // baseline of `/genres/3`.
        assertEquals(2, store.collections.size)
        assertEquals(listOf(1), store.collectionOf("genre", listOf(3))?.trackIds)
        assertEquals(listOf(2), store.collectionOf("genre", listOf(3, 7))?.trackIds)
    }

    @Test
    fun `a song deleted on purpose stays deleted until it is asked for again`() {
        store.exclude(listOf(3))
        assertEquals(listOf(3), store.snapshot.excluded)

        store.unexclude(listOf(3))
        assertTrue(store.snapshot.excluded.isEmpty())
    }

    @Test
    fun `a playlist made on this phone keeps its songs when it gets a real id`() {
        store.put(store(1))
        store.rememberCollection(OfflineCollection(kind = "playlist", id = -1, name = "Flug", trackIds = listOf(1)))

        store.remapCollection("playlist", local = -1, real = 42, name = "Flug")

        assertNull(store.collectionOf("playlist", listOf(-1)))
        assertEquals(listOf(1), store.collectionOf("playlist", listOf(42))?.trackIds)
        assertEquals("Flug", store.collectionOf("playlist", listOf(42))?.name)
    }

    @Test
    fun `a song can be put into a playlist and taken out again without a server`() {
        store.addToCollection(7, 1, name = "Abends")
        store.addToCollection(7, 2)
        assertEquals(listOf(1, 2), store.collectionOf("playlist", listOf(7))?.trackIds)
        assertEquals("Abends", store.collectionOf("playlist", listOf(7))?.name)

        store.removeFromCollection(7, 1)
        assertEquals(listOf(2), store.collectionOf("playlist", listOf(7))?.trackIds)

        // Twice is once: a song sits in a playlist or it does not.
        store.addToCollection(7, 2)
        assertEquals(listOf(2), store.collectionOf("playlist", listOf(7))?.trackIds)
    }

    @Test
    fun `a star given without a server is on the row after a restart`() {
        store.put(store(1))
        store.applyRating(1, 4)

        assertEquals(4, DownloadStore(root).entryOf(1)?.track?.stars)
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
    fun `clearing takes every file, and leaves the login alone`() {
        store.put(store(1))
        store.rememberAccount(Bootstrap(user = User(id = 1, username = "alex")))

        store.clear()

        assertEquals(0, store.count)
        assertTrue(store.audioDir.listFiles().orEmpty().isEmpty())
        assertTrue(DownloadStore(root).snapshot.tracks.isEmpty())
        // The part that is not obvious and is the whole reason clear() is not a
        // wipe: throwing away the songs on this phone says nothing about who is
        // logged in. It used to take the account too, and a cold start with no
        // network then offered the login screen - for a server that was not
        // there, to somebody who had never been logged out.
        assertEquals("alex", store.snapshot.account?.user?.username)
        assertEquals("alex", DownloadStore(root).snapshot.account?.user?.username)
    }

    @Test
    fun `logging out is the one thing that takes the account away`() {
        store.rememberAccount(Bootstrap(user = User(id = 1, username = "alex")))

        store.forgetAccount()

        assertNull(store.snapshot.account)
        assertNull(DownloadStore(root).snapshot.account)
    }

    @Test
    fun `the last look at the account survives, or nothing could be drawn offline`() {
        store.rememberAccount(
            Bootstrap(user = User(id = 1, username = "alex", displayName = "Alex"), siteName = "Sonorus")
        )

        assertEquals("Alex", DownloadStore(root).snapshot.account?.user?.displayName)
    }

    @Test
    fun `an unreadable index costs the downloads and not the login`() {
        store.put(store(1))
        store.rememberAccount(Bootstrap(user = User(id = 1, username = "alex")))
        File(root, "library.json").writeText("{ half a file")

        val again = DownloadStore(root)

        assertEquals(0, again.count)
        assertEquals("alex", again.snapshot.account?.user?.username)
    }

    @Test
    fun `an index from an older version costs the downloads and not the login`() {
        store.put(store(1))
        store.rememberAccount(Bootstrap(user = User(id = 1, username = "alex")))
        // What an app update that bumped the snapshot version leaves behind.
        File(root, "library.json").writeText("""{"version":0,"tracks":[]}""")

        val again = DownloadStore(root)

        assertEquals(0, again.count)
        assertEquals("alex", again.snapshot.account?.user?.username)
    }

    @Test
    fun `an account written into the old index is taken over rather than dropped`() {
        // Exactly the file an app installed before the account moved out has.
        File(root, "library.json").writeText(
            """{"version":1,"tracks":[],"covers":[],"playlists":[],"genres":[],""" +
                """"account":{"user":{"id":1,"username":"alex","displayName":"Alex"}}}"""
        )

        val again = DownloadStore(root)

        assertEquals("Alex", again.snapshot.account?.user?.displayName)
        // And it is moved into its own file, so the next version bump cannot
        // take it either.
        assertTrue(File(root, "account.json").isFile)
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
    fun `the place in a book is written onto the download and survives a restart`() {
        val part = Track(id = 1, title = "Teil 1", audiobookId = 100, bookKind = "book", duration = 600.0)
        store.put(DownloadedTrack(track = part, file = "1.mp3", bytes = 32))

        store.applyProgress(1, position = 120.0, completed = false)

        assertEquals(120.0, store.entryOf(1)?.track?.position ?: 0.0, 0.001)
        assertEquals(120.0, store.entryOf(1)?.track?.resumeAt ?: 0.0, 0.001)
        assertEquals(120.0, DownloadStore(root).entryOf(1)?.track?.position ?: 0.0, 0.001)

        // Once it is done with, playback picks up at the front of it again -
        // the same rule the server's `partsOf` applies.
        store.applyProgress(1, position = 599.0, completed = true)
        assertEquals(0.0, store.entryOf(1)?.track?.resumeAt ?: -1.0, 0.001)
        assertTrue(store.entryOf(1)?.track?.completed == true)
    }

    @Test
    fun `a song has no place to remember, so none is written`() {
        store.put(store(1))

        store.applyProgress(1, position = 45.0, completed = false)

        assertEquals(0.0, store.entryOf(1)?.track?.position ?: -1.0, 0.001)
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
