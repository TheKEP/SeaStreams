package com.seastreams.tv.ui.screens.catalog

import app.cash.turbine.test
import com.seastreams.tv.data.catalog.LoadedCatalogStore
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.domain.model.CatalogRow
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.usecase.CatalogLoader
import com.seastreams.tv.domain.usecase.GetViewMoreUseCase
import com.seastreams.tv.testutil.testProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class ViewMoreViewModelTest {

    private val loader = mockk<CatalogLoader>()
    private val profiles = mockk<ProfileRepository>()
    private val store = LoadedCatalogStore(Clock.systemUTC())
    private val adult = testProfile(1, isKids = false)
    private val source = CatalogSource.Discover(MediaType.MOVIE)
    private val seed = CatalogRow(items(1..20), hasMore = true)
    private val full = CatalogRow(items(1..100), hasMore = false)

    /** Completes the network expansion when the test says so. */
    private val expansion = CompletableDeferred<CatalogRow>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { profiles.observeProfile(adult.id) } returns flowOf(adult)
        coEvery { loader.expand(any(), any(), any(), any(), any()) } coAnswers { expansion.await() }
        coEvery { loader.load(any(), any(), any()) } returns full
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ViewMoreViewModel(profiles, GetViewMoreUseCase(loader, store))

    @Test
    fun `initial state carries the row's titles synchronously, before any load`() {
        store.recordRow(adult.id, source, null, seed)

        val initial = viewModel().initialState(adult.id, source)

        assertEquals(ViewMoreUiState.Loaded(ViewMoreRequest(adult.id, source), seed.items, loadingMore = true), initial)
        coVerify(exactly = 0) { loader.expand(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { loader.load(any(), any(), any()) }
    }

    @Test
    fun `seed is emitted first, then the expanded list with the seed as its prefix`() = runTest(UnconfinedTestDispatcher()) {
        store.recordRow(adult.id, source, null, seed)
        val viewModel = viewModel()
        val request = ViewMoreRequest(adult.id, source)

        viewModel.uiState.test {
            assertEquals(ViewMoreUiState.Loading(null), awaitItem())
            viewModel.show(adult.id, source)
            assertEquals(ViewMoreUiState.Loaded(request, seed.items, loadingMore = true), awaitItem())
            expectNoEvents() // still waiting on pages 2..5

            expansion.complete(full)
            val expanded = awaitItem() as ViewMoreUiState.Loaded
            assertEquals(full.items, expanded.items)
            assertEquals(seed.items, expanded.items.take(seed.items.size))
            assertEquals(false, expanded.loadingMore)
        }
        // Only the missing pages are fetched; the row itself is never reloaded.
        coVerify(exactly = 1) { loader.expand(source, null, GetViewMoreUseCase.VIEW_MORE_CAP, seed, 1) }
        coVerify(exactly = 0) { loader.load(any(), any(), any()) }
    }

    @Test
    fun `re-opening the same View More is instant and fetches nothing`() = runTest(UnconfinedTestDispatcher()) {
        store.recordRow(adult.id, source, null, seed)
        expansion.complete(full)
        GetViewMoreUseCase(loader, store)(adult, source) // the first View More visit

        val reopened = viewModel()
        assertEquals(ViewMoreUiState.Loaded(ViewMoreRequest(adult.id, source), full.items), reopened.initialState(adult.id, source))
        reopened.uiState.test {
            skipItems(1)
            reopened.show(adult.id, source)
            assertEquals(full.items, (awaitItem() as ViewMoreUiState.Loaded).items)
            expectNoEvents()
        }
        coVerify(exactly = 1) { loader.expand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a row recorded by a later Home visit does not replace the expanded list`() {
        store.recordExpanded(adult.id, source, null, full)
        store.recordRow(adult.id, source, null, seed)

        assertEquals(full.items, (viewModel().initialState(adult.id, source) as ViewMoreUiState.Loaded).items)
    }

    @Test
    fun `a failed expansion keeps the row's titles on screen`() = runTest(UnconfinedTestDispatcher()) {
        store.recordRow(adult.id, source, null, seed)
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1)
            viewModel.show(adult.id, source)
            skipItems(1)
            expansion.completeExceptionally(IOException("offline"))
            assertEquals(ViewMoreUiState.Loaded(ViewMoreRequest(adult.id, source), seed.items), awaitItem())
        }
    }

    @Test
    fun `without a cached row the screen starts loading and does a full load`() = runTest(UnconfinedTestDispatcher()) {
        val fullLoad = CompletableDeferred<CatalogRow>()
        coEvery { loader.load(any(), any(), any()) } coAnswers { fullLoad.await() }
        val viewModel = viewModel()
        val request = ViewMoreRequest(adult.id, source)
        assertEquals(ViewMoreUiState.Loading(request), viewModel.initialState(adult.id, source))

        viewModel.uiState.test {
            skipItems(1)
            viewModel.show(adult.id, source)
            assertEquals(ViewMoreUiState.Loading(request), awaitItem())
            fullLoad.complete(full)
            assertEquals(ViewMoreUiState.Loaded(request, full.items), awaitItem())
        }
    }

    @Test
    fun `editing a profile drops its cached rows`() {
        store.recordRow(adult.id, source, null, seed)
        store.forget(adult.id)

        assertEquals(ViewMoreUiState.Loading(ViewMoreRequest(adult.id, source)), viewModel().initialState(adult.id, source))
    }

    private fun items(ids: IntRange) = ids.map { id ->
        MediaItem(
            tmdbId = id,
            mediaType = MediaType.MOVIE,
            title = "Title $id",
            overview = "",
            posterUrl = null,
            backdropUrl = null,
            heroBackdropUrl = null,
            logoUrl = null,
            year = null,
            ratingPercent = null,
            genres = emptyList(),
            runtimeMinutes = null,
            certification = null,
            isAdultContent = false,
        )
    }
}
