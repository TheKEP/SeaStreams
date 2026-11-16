package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.core.network.MdbListApiService
import com.seastreams.tv.core.network.MdbListItem
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.testutil.FakeCatalogDao
import com.seastreams.tv.testutil.movieDetails
import com.seastreams.tv.testutil.testProfile
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock

/** Shared fixture for the [GetHomeCatalogsUseCase] row and hero test classes. */
abstract class HomeCatalogsTestBase {

    protected val mdbList = mockk<MdbListApiService>()
    protected val tmdb = mockk<TmdbApiService>()
    protected val catalogDao = FakeCatalogDao()
    protected val useCase = GetHomeCatalogsUseCase(
        Lazy { mdbList },
        MediaHydrator(Lazy { tmdb }, TmdbImageUrls(ApiEndpoints())),
        catalogDao,
        AgeRestrictionEngine(),
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        Clock.systemUTC(),
    )
    protected val adult = testProfile(1, isKids = false)
    protected val kids = testProfile(2, isKids = true)
    protected val spec = CatalogRowSpec("test", "Test", "https://mdblist.com/lists/owner/test")

    /** Movies 1..[count]; ids divisible by 3 are rated R, the rest G. */
    protected fun givenMovieList(url: String, count: Int) {
        coEvery { mdbList.fetchList(url) } returns (1..count).map { MdbListItem(tmdbId = it, mediaTypeRaw = "movie") }
        coEvery { tmdb.getMovieDetails(any()) } answers {
            val id = firstArg<Int>()
            movieDetails(id, if (id % 3 == 0) "R" else "G")
        }
    }
}
