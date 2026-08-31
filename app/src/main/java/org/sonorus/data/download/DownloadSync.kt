package org.sonorus.data.download

import org.sonorus.data.Library
import org.sonorus.data.model.Track

/**
 * Keeps the downloads in step with the collections they came from.
 *
 * Florian's rule, and it is the whole feature: **a downloaded playlist is a
 * standing order, not a copy taken once.** A song added to it is fetched, a song
 * taken out of it goes - and a song that goes from one list but still sits in
 * another downloaded one stays, because something still wants it here.
 *
 * Three things make that work, and each of them is a decision worth keeping:
 *
 *  - **The diff is against the last server answer, not against the disk.** Each
 *    collection stores what it held when it was last looked at
 *    ([OfflineCollection.trackIds]); what appeared since is fetched, what
 *    disappeared is let go. Diffing against the files instead would have no way
 *    to tell "the server dropped this" from "the download has not run yet".
 *  - **Deleting asks who else wants it.** A track is only really deleted when
 *    no other collection holds it and nobody fetched it by hand - see
 *    [DownloadStore.isHeld] and [OfflineSnapshot.manual].
 *  - **A song deleted by hand stays deleted** ([OfflineSnapshot.excluded]).
 *    Without that, a collection would fetch it again on the next sync, and
 *    "remove this one download" would be a button that does nothing.
 *
 * Nothing here polls. A reconcile runs when the app already knows something
 * changed: at start, after the app itself edits a playlist, and when a screen
 * that has just loaded a collection draws its download button - that last one
 * costs no request at all, because the list is already in hand.
 */
class DownloadSync(
    private val lib: Library,
    private val downloads: Downloads,
    private val store: DownloadStore,
) {

    /** What one reconcile did, so a toast can say it. */
    data class Change(val added: Int = 0, val removed: Int = 0) {
        val any: Boolean get() = added > 0 || removed > 0

        operator fun plus(other: Change) = Change(added + other.added, removed + other.removed)
    }

    /**
     * Every remembered collection, asked of the server one after the other.
     *
     * One request per collection and no parallelism: this runs at start next to
     * everything else the app is doing, and a handful of small reads in a row
     * costs less than a burst that competes with the first screen.
     */
    suspend fun reconcileAll(): Change {
        if (lib.offline.value) return Change()
        var total = Change()
        for (collection in store.collections) {
            total += reconcileOne(collection)
        }
        return total
    }

    /** Every collection of one kind - the star lists after a rating, say. */
    suspend fun reconcileKind(kind: String): Change {
        if (lib.offline.value) return Change()
        var total = Change()
        for (collection in store.collections.filter { it.kind == kind }) {
            total += reconcileOne(collection)
        }
        return total
    }

    /** One collection, if it is one this phone keeps in step. */
    suspend fun reconcile(kind: String, ids: List<Int>): Change {
        if (lib.offline.value) return Change()
        val collection = store.collectionOf(kind, ids) ?: return Change()
        return reconcileOne(collection)
    }

    private suspend fun reconcileOne(collection: OfflineCollection): Change {
        val current = runCatching { fetch(collection) }.getOrNull() ?: return Change()
        return apply(collection, current)
    }

    /**
     * The same reconcile against a list the caller already has.
     *
     * This is the cheap path and the one that runs most often: a screen that has
     * just loaded its playlist knows exactly what the server says it holds, so
     * the sync needs no request of its own.
     */
    fun apply(collection: OfflineCollection, current: List<Track>): Change {
        val here = current.filterNot { it.missing }
        val plan = Reconcile.plan(
            previous = collection.trackIds,
            current = here.map { it.id },
            downloaded = store.snapshot.tracks.map { it.track.id }.toSet(),
            excluded = store.snapshot.excluded.toSet(),
            heldElsewhere = store.heldBy(exceptKey = collection.key),
        )
        // The baseline moves first. If the fetch below is interrupted, the songs
        // are still in the collection and the next reconcile queues what is
        // missing - where a baseline written afterwards would have lost them.
        store.rememberCollection(collection.copy(trackIds = here.map { it.id }))
        if (plan.add.isNotEmpty()) {
            downloads.add(here.filter { it.id in plan.add }, manual = false)
        }
        for (id in plan.delete) downloads.remove(id)
        return Change(added = plan.add.size, removed = plan.delete.size)
    }

    /**
     * What the server says the collection holds now, or `null` for a kind this
     * sync does not know.
     *
     * **Null and not an empty list**, and the difference is the whole
     * collection: [apply] reads its argument as the complete current contents,
     * so an empty list means "the server dropped every song" and deletes them
     * all. A kind that is registered by a screen but forgotten here would land
     * exactly there. Null is the only answer that means "no idea", and
     * [reconcileOne] leaves the collection alone on it.
     */
    private suspend fun fetch(collection: OfflineCollection): List<Track>? = when (collection.kind) {
        "playlist" -> lib.playlist(collection.id).tracks
        "album" -> lib.album(collection.id).album.tracks
        "artist" -> lib.artist(collection.id).artist.tracks
        "genre" -> lib.genre(collection.selection).genre.tracks
        "stars" -> lib.stars(collection.selection).tracks
        else -> null
    }
}

/**
 * The diff itself, with nothing around it.
 *
 * Pure on purpose, like [Offline]: this is the piece that decides whether a file
 * is deleted, so it is the piece that has to be provable on a plain JVM rather
 * than on a phone.
 */
object Reconcile {

    data class Plan(val add: List<Int> = emptyList(), val delete: List<Int> = emptyList())

    /**
     * @param previous what the collection held when the server was last asked
     * @param current what it holds now
     * @param downloaded every song that is on this phone
     * @param excluded songs the user deleted by hand although a collection holds them
     * @param heldElsewhere songs another collection wants, or somebody fetched by hand
     */
    fun plan(
        previous: List<Int>,
        current: List<Int>,
        downloaded: Set<Int>,
        excluded: Set<Int> = emptySet(),
        heldElsewhere: Set<Int> = emptySet(),
    ): Plan = Plan(
        // Everything the collection holds and the phone does not - which also
        // picks up what an interrupted run never got to, not only what is new.
        add = current.filter { it !in downloaded && it !in excluded }.distinct(),
        // Only what really left the collection, and only if nothing else wants
        // it. A song still on the phone that was never in this list is none of
        // its business.
        delete = previous.filter { it !in current && it in downloaded && it !in heldElsewhere }.distinct(),
    )
}
