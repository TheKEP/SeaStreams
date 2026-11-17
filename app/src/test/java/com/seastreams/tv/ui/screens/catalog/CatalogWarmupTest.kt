package com.seastreams.tv.ui.screens.catalog

import app.cash.turbine.test
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.data.catalog.DiscoverSnapshotStore
import com.seastreams.tv.data.catalog.LoadedCatalogStore
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.domain.model.CatalogRow
import com.seastreams.tv.domain.model.CatalogRowState
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.domain.model.StreamingProvider
import com.seastreams.tv.domain.usecase.CatalogLoader
import com.seastreams.tv.domain.usecase.GetDiscoverCatalogsUseCase
import com.seastreams.tv.testutil.testProfile
import com.seastreams.tv.ui.screens.home.HomeImagePrewarmer
import com.seastreams.tv.ui.screens.home.ImageTarget
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogWarmupTest {

    private val loader = mockk<CatalogLoader>()
    private val settings = mockk<SettingsDataStore>()
    private val profiles = mockk<ProfileRepository>()
    private val images = mockk<HomeImagePrewarmer>()
    private val store = DiscoverSnapshotStore()
    private val adult = testProfile(1, isKids = false)
    private val kids = testProfile(2, isKids = true)

    init {
        every { settings.enabledProviders } returns flowOf(listOf(StreamingProvider.NETFLIX))
        every { profiles.observeProfile(adult.id) } returns flowOf(adult)
        coEvery { loader.load(any(), any(), any()) } answers { rowFor(firstArg()) }
        coEvery { images.warm(any()) } just Runs
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.useCase() = GetDiscoverCatalogsUseCase(loader, settings, store, LoadedCatalogStore(Clock.systemUTC()), backgroundScope, Clock.systemUTC())

    private fun TestScope.manager(discover: GetDiscoverCatalogsUseCase) =
        CatalogWarmupManager(discover, images, backgroundScope, StandardTestDispatcher(testScheduler), DELAY)

    private fun List<CatalogRowState>.allLoaded() = isNotEmpty() && all { it.content is RowContent.Loaded }

    @Test
    fun `warmed tab hydrates the ViewModel's initial state synchronously`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val discover = useCase()
        discover.warm(adult, MediaType.MOVIE)
        val viewModel = DiscoverViewModel(profiles, settings, discover)

        val initial = viewModel.initialState(adult.id, MediaType.MOVIE)

        assertEquals(adult, initial.profile)
        assertEquals(MediaType.MOVIE, initial.mediaType)
        assertEquals(listOf("movie-popular", "movie-netflix"), initial.rows.map { it.spec.id })
        assertTrue(initial.rows.allLoaded())
        assertEquals(listOf(StreamingProvider.NETFLIX), initial.enabledProviders)
    }

    @Test
    fun `warmed tab never emits an empty or loading frame once shown`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val discover = useCase()
        discover.warm(adult, MediaType.TV)
        val viewModel = DiscoverViewModel(profiles, settings, discover)

        viewModel.uiState.test {
            assertNull(awaitItem().profile) // stateIn's placeholder before any profile is requested
            viewModel.show(adult.id, MediaType.TV)
            val first = awaitItem()
            assertEquals(adult, first.profile)
            assertTrue(first.rows.allLoaded(), "first emission for the profile must already carry loaded rows")
            expectNoEvents()
        }
    }

    @Test
    fun `without a warm-up the initial state is empty`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = DiscoverViewModel(profiles, settings, useCase())

        assertEquals(DiscoverUiState(), viewModel.initialState(adult.id, MediaType.MOVIE))
    }

    @Test
    fun `warm skips Kids profiles`() = runTest {
        val discover = useCase()

        assertNull(discover.warm(kids, MediaType.MOVIE))
        assertNull(discover.warm(kids, MediaType.TV))
        assertNull(discover.snapshot(kids.id, MediaType.MOVIE))
        coVerify(exactly = 0) { loader.load(any(), any(), any()) }
    }

    @Test
    fun `editing or deleting a profile drops its snapshot`() = runTest {
        val discover = useCase()
        discover.warm(adult, MediaType.MOVIE)
        assertNotNull(discover.snapshot(adult.id, MediaType.MOVIE))

        store.forget(adult.id)

        assertNull(discover.snapshot(adult.id, MediaType.MOVIE))
    }

    @Test
    fun `adult warm-up waits for the delay then warms both tabs and their first Popular posters`() = runTest {
        val discover = useCase()
        val targets = slot<List<ImageTarget>>()
        coEvery { images.warm(capture(targets)) } just Runs

        manager(discover).schedule(adult)
        runCurrent()
        advanceTimeBy(DELAY - 1)
        coVerify(exactly = 0) { loader.load(any(), any(), any()) }
        advanceTimeBy(2) // advanceUntilIdle would skip backgroundScope work
        runCurrent()

        assertNotNull(discover.snapshot(adult.id, MediaType.MOVIE))
        assertNotNull(discover.snapshot(adult.id, MediaType.TV))
        // Popular + Netflix for each tab.
        coVerify(exactly = 4) { loader.load(any(), any(), any()) }
        val expected = listOf(MediaType.MOVIE, MediaType.TV).flatMap { type ->
            (1..CatalogWarmupManager.WARM_POSTERS_PER_TAB).map { posterUrl(CatalogSource.Discover(type), it) }
        }
        assertEquals(expected, targets.captured.map { it.url })
    }

    @Test
    fun `scheduling a Kids profile cancels a pending adult warm-up and warms nothing`() = runTest {
        val discover = useCase()
        val manager = manager(discover)

        manager.schedule(adult)
        advanceTimeBy(DELAY / 2)
        manager.schedule(kids)
        advanceTimeBy(DELAY * 2) // past the adult warm-up's original start time
        runCurrent()

        coVerify(exactly = 0) { loader.load(any(), any(), any()) }
        coVerify(exactly = 0) { images.warm(any()) }
        assertNull(discover.snapshot(adult.id, MediaType.MOVIE))
        assertNull(discover.snapshot(kids.id, MediaType.MOVIE))
    }

    @Test
    fun `poster targets fall back to the backdrop and skip failed rows`() {
        val noPoster = item(1, poster = null, backdrop = "https://img.test/b1.jpg")
        val row = CatalogRow(listOf(noPoster) + (2..10).map { item(it, poster = "https://img.test/p$it.jpg") }, hasMore = false)

        val targets = popularPosterTargets(listOf(row, null), perRow = 3)

        assertEquals(listOf("https://img.test/b1.jpg", "https://img.test/p2.jpg", "https://img.test/p3.jpg"), targets.map { it.url })
    }

    @Test
    fun `movies and tv tabs never contain Stremio addon catalog rows`() = runTest {
        val discover = useCase()
        MediaType.entries.forEach { type ->
            val rows = discover.observeRows(adult, type).first()
            assertEquals(listOf("Popular", "Netflix"), rows.map { it.spec.title.substringBefore(' ') })
            assertTrue(rows.none { it.spec.source is CatalogSource.Addon }, "$type tab must not show addon catalogs")
        }
    }

    private fun rowFor(source: CatalogSource) =
        CatalogRow((1..10).map { item(it, poster = posterUrl(source, it)) }, hasMore = false)

    private fun posterUrl(source: CatalogSource, index: Int) = "https://img.test/${source.hashCode()}/$index.jpg"

    private fun item(id: Int, poster: String?, backdrop: String? = null) = MediaItem(
        tmdbId = id,
        mediaType = MediaType.MOVIE,
        title = "Title $id",
        overview = "",
        posterUrl = poster,
        backdropUrl = backdrop,
        heroBackdropUrl = null,
        logoUrl = null,
        year = null,
        ratingPercent = null,
        genres = emptyList(),
        runtimeMinutes = null,
        certification = null,
        isAdultContent = false,
    )

    private companion object {
        const val DELAY = 600L
    }
}
