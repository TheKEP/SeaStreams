package com.seastreams.tv.testutil

import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.core.network.MdbListApiService
import com.seastreams.tv.core.network.StremioAddonClient
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.data.catalog.GenreRepository
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.usecase.AddonCatalogLoader
import com.seastreams.tv.domain.usecase.CatalogLoader
import dagger.Lazy
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** A real [CatalogLoader] (and its [AddonCatalogLoader]) over mocked MDBList, TMDB and addon clients. */
fun testCatalogLoader(
    mdbList: MdbListApiService,
    tmdb: TmdbApiService,
    addonClient: StremioAddonClient = mockk(),
    clock: Clock = TEST_CLOCK,
): CatalogLoader {
    val images = TmdbImageUrls(ApiEndpoints())
    val hydrator = MediaHydrator(Lazy { tmdb }, images)
    val genres = GenreRepository(hydrator)
    return CatalogLoader(
        Lazy { mdbList },
        hydrator,
        images,
        genres,
        AgeRestrictionEngine(),
        AddonCatalogLoader(Lazy { addonClient }, hydrator, images, genres),
        clock,
    )
}

/** Fixed "today" (2026-11-21 UTC) for date-windowed TMDB queries. */
val TEST_CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC)
