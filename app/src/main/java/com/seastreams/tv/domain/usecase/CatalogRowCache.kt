package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.util.SharedLoadCache
import com.seastreams.tv.data.catalog.LoadedCatalogStore
import com.seastreams.tv.domain.model.AgeRestriction
import com.seastreams.tv.domain.model.CatalogRow
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.CatalogRowState
import com.seastreams.tv.domain.model.CatalogSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow

/**
 * In-memory catalog rows keyed by source and age restriction, shared by the Home and Discover use
 * cases. Each row handed to a screen through [observe] is also recorded in [LoadedCatalogStore], so
 * that row's View More opens with the same titles on its first frame.
 */
internal class CatalogRowCache(
    private val loadedCatalogs: LoadedCatalogStore,
    scope: CoroutineScope,
    ttlMillis: Long,
    nowMillis: () -> Long,
    private val load: suspend (CatalogRowSpec, AgeRestriction?) -> CatalogRow,
) {
    private data class Key(val source: CatalogSource, val restriction: AgeRestriction?)

    private val cache = SharedLoadCache<Key, CatalogRow>(scope, ttlMillis, nowMillis)

    /** The finished, still-fresh row, or `null`. Never starts a load or suspends. */
    fun peek(spec: CatalogRowSpec, restriction: AgeRestriction?): CatalogRow? =
        cache.peek(Key(spec.source, restriction))

    /** The running or finished load of [spec]; it keeps running if the caller stops waiting. */
    fun start(spec: CatalogRowSpec, restriction: AgeRestriction?): Deferred<CatalogRow> =
        cache.start(Key(spec.source, restriction)) { load(spec, restriction) }

    /** [specs] for [profileId] as they load (see [rowStates]); each row shown is recorded for View More. */
    fun observe(profileId: Long, specs: List<CatalogRowSpec>, restriction: AgeRestriction?): Flow<List<CatalogRowState>> {
        fun shown(spec: CatalogRowSpec, row: CatalogRow): CatalogRow =
            row.also { loadedCatalogs.recordRow(profileId, spec.source, restriction, it) }
        return rowStates(specs, cached = { spec -> peek(spec, restriction)?.let { shown(spec, it) } }) { spec ->
            shown(spec, start(spec, restriction).await())
        }
    }
}
