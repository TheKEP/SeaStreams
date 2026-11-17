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

/** A real [CatalogLoader] (and its [AddonCatalogLoader]) over mocked MDBList, TMDB and addon clients. */
fun testCatalogLoader(
    mdbList: MdbListApiService,
    tmdb: TmdbApiService,
    addonClient: StremioAddonClient = mockk(),
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
    )
}
