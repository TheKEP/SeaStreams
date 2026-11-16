package com.seastreams.tv.data.catalog

import com.seastreams.tv.domain.model.AgeRestriction
import com.seastreams.tv.domain.model.CatalogRow
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.MediaItem
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What is already in memory for one catalog of one profile: the row's titles, or the full View More
 * list once it has been expanded ([complete]). [hasMore] says whether the source goes past [items].
 */
data class LoadedCatalog(
    val items: List<MediaItem>,
    val hasMore: Boolean,
    val complete: Boolean,
    val restriction: AgeRestriction?,
)

/**
 * App-wide memory of the catalogs each profile has loaded, keyed by profile id and [CatalogSource].
 * Home / Movies / TV Shows record every row they show, and View More records its expanded list, so
 * opening (or re-opening) View More draws the titles already loaded on its very first frame.
 *
 * Everything stored here has already passed the age filter for [LoadedCatalog.restriction], and a
 * profile's entries are dropped whenever it is edited or deleted, so a tightened age ceiling can never
 * be shown titles loaded under the old one (CLAUDE.md rule 6). Entries expire after [TTL_MILLIS]; the
 * 6–12h HTTP cache keeps the reload cheap after that.
 */
@Singleton
class LoadedCatalogStore @Inject constructor(private val clock: Clock) {

    private data class Key(val profileId: Long, val source: CatalogSource)

    private class Entry(val catalog: LoadedCatalog, val recordedAtMillis: Long)

    private val entries = ConcurrentHashMap<Key, Entry>()

    /** Records the row [profileId] is showing for [source]; never replaces a fresh expanded list. */
    fun recordRow(profileId: Long, source: CatalogSource, restriction: AgeRestriction?, row: CatalogRow) {
        val fresh = Entry(LoadedCatalog(row.items, row.hasMore, complete = !row.hasMore, restriction), clock.millis())
        entries.compute(Key(profileId, source)) { _, old ->
            val keepOld = old != null && isFresh(old) && old.catalog.complete && old.catalog.restriction == restriction
            if (keepOld) old else fresh
        }
    }

    /** Records the full View More list of [source] for [profileId]. */
    fun recordExpanded(profileId: Long, source: CatalogSource, restriction: AgeRestriction?, row: CatalogRow) {
        entries[Key(profileId, source)] =
            Entry(LoadedCatalog(row.items, row.hasMore, complete = true, restriction), clock.millis())
    }

    /**
     * The fresh catalog held for [profileId] and [source], or `null`. Never suspends, so a ViewModel can
     * use it for its first frame before the profile itself has been read.
     */
    fun peek(profileId: Long, source: CatalogSource): LoadedCatalog? {
        val key = Key(profileId, source)
        val entry = entries[key] ?: return null
        if (isFresh(entry)) return entry.catalog
        entries.remove(key, entry)
        return null
    }

    /** Like [peek], but only when it was filtered for exactly [restriction]. */
    fun peek(profileId: Long, source: CatalogSource, restriction: AgeRestriction?): LoadedCatalog? =
        peek(profileId, source)?.takeIf { it.restriction == restriction }

    /** Drops everything held for [profileId]; called when the profile is edited or deleted. */
    fun forget(profileId: Long) {
        entries.keys.removeIf { it.profileId == profileId }
    }

    private fun isFresh(entry: Entry): Boolean = clock.millis() - entry.recordedAtMillis < TTL_MILLIS

    companion object {
        /** Same lifetime as the Home / Discover row caches. */
        const val TTL_MILLIS = 10 * 60 * 1000L
    }
}
