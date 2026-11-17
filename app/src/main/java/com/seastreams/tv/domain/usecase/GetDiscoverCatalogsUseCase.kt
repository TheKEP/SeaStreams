package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.util.ApplicationScope
import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.data.catalog.DiscoverInputs
import com.seastreams.tv.data.catalog.DiscoverSnapshotStore
import com.seastreams.tv.data.catalog.LoadedCatalogStore
import com.seastreams.tv.domain.model.CatalogRow
import com.seastreams.tv.domain.model.CatalogRowState
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.domain.model.StreamingProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** A Movies / TV Shows tab as it can be drawn right now from memory, without waiting on anything. */
data class DiscoverSnapshot(
    val profile: ProfileEntity,
    val enabledProviders: List<StreamingProvider>,
    val rows: List<CatalogRowState>,
)

/**
 * Rows of the Movies and TV Shows tabs (PRD §5): `Popular`, each enabled streaming service via TMDB
 * `/discover` (HTTP-cached 6h, 429s retried). Stremio addon catalogs are Home-only and never appear
 * here. Titles are age-filtered by [CatalogLoader] before they leave this class (CLAUDE.md rule 6).
 *
 * Loaded rows are kept in memory for [ROW_TTL_MILLIS], so switching tabs or toggling a service back
 * on shows them instantly; [warm] fills that memory ahead of the first visit, and [snapshot] lets a
 * tab draw already-loaded rows on its very first frame.
 */
@Singleton
class GetDiscoverCatalogsUseCase @Inject constructor(
    private val loader: CatalogLoader,
    private val settings: SettingsDataStore,
    private val snapshots: DiscoverSnapshotStore,
    loadedCatalogs: LoadedCatalogStore,
    @ApplicationScope scope: CoroutineScope,
    clock: Clock,
) {
    private val rowCache = CatalogRowCache(loadedCatalogs, scope, ROW_TTL_MILLIS, clock::millis) { spec, restriction ->
        loader.load(spec.source, restriction, GetHomeCatalogsUseCase.ROW_CAP)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeRows(profile: ProfileEntity, mediaType: MediaType): Flow<List<CatalogRowState>> {
        val restriction = profile.toAgeRestriction()
        return settings.enabledProviders
            .map { providers -> DiscoverInputs(profile, providers, DiscoverCatalogSources.rowSpecs(mediaType, providers)) }
            .distinctUntilChanged()
            .onEach { snapshots.record(mediaType, it) }
            .flatMapLatest { inputs -> rowCache.observe(profile.id, inputs.specs, restriction) }
    }

    /**
     * [profileId]'s tab as last resolved, with every row already in memory filled in, or `null` when
     * nothing usable is cached: nothing recorded for the profile, a Kids profile, or `Popular` not
     * loaded. Never suspends, so a ViewModel can use it for its very first state.
     */
    fun snapshot(profileId: Long, mediaType: MediaType): DiscoverSnapshot? {
        val inputs = snapshots.lookup(profileId, mediaType) ?: return null
        if (inputs.profile.isKids) return null
        val restriction = inputs.profile.toAgeRestriction()
        val rows = initialRowStates(inputs.specs) { spec -> rowCache.peek(spec, restriction) }
        if (rows.firstOrNull()?.content !is RowContent.Loaded) return null
        return DiscoverSnapshot(inputs.profile, inputs.enabledProviders, rows)
    }

    /**
     * Loads every row of [profile]'s [mediaType] tab into memory (the HTTP cache keeps the responses
     * on disk) and records the tab's inputs for [snapshot]. Kids profiles have no such tab and load
     * nothing. Rows that fail are left out; the tab retries them when opened.
     * @return the tab's loaded `Popular` row, or `null` if it failed or nothing was loaded.
     */
    suspend fun warm(profile: ProfileEntity, mediaType: MediaType): CatalogRow? {
        if (profile.isKids) return null
        val restriction = profile.toAgeRestriction()
        val providers = settings.enabledProviders.first()
        val specs = DiscoverCatalogSources.rowSpecs(mediaType, providers)
        snapshots.record(mediaType, DiscoverInputs(profile, providers, specs))
        val loads = specs.map { spec -> rowCache.start(spec, restriction) }
        val rows = loads.map { orNullUnlessCancelled { it.await() } }
        return rows.firstOrNull()
    }

    companion object {
        const val ROW_TTL_MILLIS = 10 * 60 * 1000L
    }
}
