package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.network.MdbListApiService
import com.seastreams.tv.core.network.MdbListItem
import com.seastreams.tv.core.network.StremioAddonClient
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.data.catalog.LoadedCatalogStore
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.testutil.FakeAddonDao
import com.seastreams.tv.testutil.movieDetails
import com.seastreams.tv.testutil.testCatalogLoader
import com.seastreams.tv.testutil.testProfile
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
    protected val addonClient = mockk<StremioAddonClient>()
    protected val addonDao = FakeAddonDao()
    protected val loader = testCatalogLoader(mdbList, tmdb, addonClient)
    protected val useCase = GetHomeCatalogsUseCase(
        loader,
        addonDao,
        LoadedCatalogStore(Clock.systemUTC()),
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        Clock.systemUTC(),
    )
    protected val adult = testProfile(1, isKids = false)
    protected val kids = testProfile(2, isKids = true)
    protected val spec = CatalogRowSpec("test", "Test", "https://mdblist.com/lists/owner/test")

    /** An enabled HTTP addon of [profileId] at `https://{host}/manifest.json` declaring [catalogsJson] (a JSON array). */
    protected fun addonEntity(
        host: String,
        sortOrder: Int,
        catalogsJson: String,
        enabled: Boolean = true,
        profileId: Long = adult.id,
    ) = AddonEntity(
        profileId = profileId,
        manifestUrl = "https://$host/manifest.json",
        manifestId = "com.example.$host",
        name = host,
        customName = null,
        enabled = enabled,
        sortOrder = sortOrder,
        manifestJson = """{"id":"com.example.$host","version":"1.0.0","name":"$host",""" +
            """"resources":["catalog"],"types":["movie","series"],"catalogs":$catalogsJson}""",
        installedAtMillis = 0,
    )

    /** Movies 1..[count]; ids divisible by 3 are rated R, the rest G. */
    protected fun givenMovieList(url: String, count: Int) {
        coEvery { mdbList.fetchList(url) } returns (1..count).map { MdbListItem(tmdbId = it, mediaTypeRaw = "movie") }
        coEvery { tmdb.getMovieDetails(any()) } answers {
            val id = firstArg<Int>()
            movieDetails(id, if (id % 3 == 0) "R" else "G")
        }
    }
}

/** The list URL of an MDBList-backed row spec. */
val CatalogRowSpec.listUrl: String get() = (source as CatalogSource.MdbList).listUrl
