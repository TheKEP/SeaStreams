package com.seastreams.tv.data.debrid.library

import com.seastreams.tv.data.debrid.api.DebridFile
import com.seastreams.tv.data.debrid.api.DebridItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Each provider's library listing and the file lists read from it (ARCHITECTURE §12), in memory only, for [TTL_MILLIS].
 * Single-flight: lookups that arrive while a listing is being fetched share that fetch (and its failure; a failed
 * fetch is never cached). An entry belongs to one credential *generation* of its provider, so a replaced key or a
 * removed (or re-added) account never reads the old account's library; [clear] (`Clear Debrid settings`) and
 * [invalidate] (a file found deleted) drop entries at once.
 */
@Singleton
class DebridLibraryCache @Inject constructor(private val clock: Clock) {
    private class Listing(val generation: Long, val result: CompletableDeferred<List<DebridItem>>) {
        var loadedAt: Long? = null
    }

    private class Files(val generation: Long, val loadedAt: Long, val files: List<DebridFile>)

    private val lock = Any()
    private val listings = HashMap<Long, Listing>()
    private val files = object : LinkedHashMap<Pair<Long, String>, Files>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Long, String>, Files>?): Boolean = size > MAX_FILE_LISTS
    }

    /** [providerId]'s listing for credential [generation]: cached, shared with a fetch in flight, or [load]ed now. */
    suspend fun library(providerId: Long, generation: Long, load: suspend () -> List<DebridItem>): List<DebridItem> {
        val (listing, owner) = synchronized(lock) {
            val current = listings[providerId]
            if (current != null && current.generation == generation && current.fresh()) {
                current to false
            } else {
                Listing(generation, CompletableDeferred()).also { listings[providerId] = it } to true
            }
        }
        if (!owner) return listing.result.await()
        return try {
            load().also { items ->
                synchronized(lock) { listing.loadedAt = clock.millis() }
                listing.result.complete(items)
            }
        } catch (e: Throwable) {
            synchronized(lock) { if (listings[providerId] === listing) listings.remove(providerId) }
            // Callers sharing a fetch its owner abandoned see a failure, not a cancellation of their own.
            listing.result.completeExceptionally(if (e is CancellationException) IOException("Library listing cancelled") else e)
            throw e
        }
    }

    /** [itemId]'s files for credential [generation], cached like the listing. */
    suspend fun files(providerId: Long, generation: Long, itemId: String, load: suspend () -> List<DebridFile>): List<DebridFile> {
        val key = providerId to itemId
        synchronized(lock) {
            val cached = files[key]
            if (cached != null && cached.generation == generation && clock.millis() - cached.loadedAt < TTL_MILLIS) return cached.files
        }
        val loaded = load()
        synchronized(lock) { files[key] = Files(generation, clock.millis(), loaded) }
        return loaded
    }

    /** Drops [providerId]'s listing and file lists (a file of it was found deleted). */
    fun invalidate(providerId: Long) = synchronized(lock) {
        listings.remove(providerId)
        files.keys.removeAll { it.first == providerId }
    }

    /** Keeps only the providers in [providerIds] (removed or disabled accounts are forgotten). */
    fun retain(providerIds: Set<Long>) = synchronized(lock) {
        listings.keys.retainAll(providerIds)
        files.keys.removeAll { it.first !in providerIds }
    }

    fun clear() = synchronized(lock) {
        listings.clear()
        files.clear()
    }

    private fun Listing.fresh(): Boolean = loadedAt.let { it == null || clock.millis() - it < TTL_MILLIS }

    companion object {
        const val TTL_MILLIS = 60_000L
        private const val MAX_FILE_LISTS = 256
    }
}
