package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.data.catalog.LoadedCatalog
import com.seastreams.tv.data.catalog.LoadedCatalogStore
import com.seastreams.tv.domain.model.CatalogRow
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.MediaItem
import javax.inject.Inject

/**
 * The View More grid (PRD §4): up to [VIEW_MORE_CAP] age-approved titles of one row's source.
 *
 * The row's titles are already in [LoadedCatalogStore] when View More opens, so [cached] can draw them
 * on the first frame; [invoke] then fetches only what the row did not have (pages 2..5 of a TMDB list,
 * in parallel) and stores the full list so re-opening the same View More is instant.
 */
class GetViewMoreUseCase @Inject constructor(
    private val loader: CatalogLoader,
    private val store: LoadedCatalogStore,
) {
    /**
     * What [profileId]'s [source] already holds in memory (its row, or its full View More list), or
     * `null`. Never suspends. Entries are dropped whenever a profile is edited or deleted, so they are
     * always filtered for the profile's current age ceiling (CLAUDE.md rule 6).
     */
    fun cached(profileId: Long, source: CatalogSource): LoadedCatalog? = store.peek(profileId, source)

    /**
     * Up to [VIEW_MORE_CAP] titles of [source] for [profile]: [seed] grown with only the missing
     * titles, or a full load when there is no seed (or one filtered for another age ceiling).
     */
    suspend operator fun invoke(profile: ProfileEntity, source: CatalogSource, seed: LoadedCatalog? = cached(profile.id, source)): List<MediaItem> {
        val restriction = profile.toAgeRestriction()
        val usable = seed?.takeIf { it.restriction == restriction }
        if (usable?.complete == true) return usable.items
        val row = if (usable == null) {
            loader.load(source, restriction, VIEW_MORE_CAP)
        } else {
            loader.expand(source, restriction, VIEW_MORE_CAP, CatalogRow(usable.items, usable.hasMore), ROW_PAGES)
        }
        store.recordExpanded(profile.id, source, restriction, row)
        return row.items
    }

    companion object {
        const val VIEW_MORE_CAP = 100

        /** TMDB pages a row's titles came from: View More only fetches the pages after these. */
        private val ROW_PAGES = CatalogLoader.pagesFor(GetHomeCatalogsUseCase.ROW_CAP)
    }
}
