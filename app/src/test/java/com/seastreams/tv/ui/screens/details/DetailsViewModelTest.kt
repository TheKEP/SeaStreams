package com.seastreams.tv.ui.screens.details

import com.seastreams.tv.data.details.RestrictedTitleException
import com.seastreams.tv.data.details.TitleDetailsRepository
import com.seastreams.tv.data.library.LibraryRepository
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.domain.playback.PlaybackProgressRecorder
import com.seastreams.tv.domain.playback.ShuffleMode
import com.seastreams.tv.domain.playback.ShuffleSession
import com.seastreams.tv.testutil.FakeLibraryDao
import com.seastreams.tv.testutil.FakeWatchProgressDao
import com.seastreams.tv.testutil.testDetails
import com.seastreams.tv.testutil.testProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModelTest {

    private val profiles = mockk<ProfileRepository>()
    private val titles = mockk<TitleDetailsRepository>()
    private val libraryDao = FakeLibraryDao()
    private val movie = testDetails(7)
    private val profile = testProfile(1, isKids = false)

    init {
        coEvery { profiles.getProfile(1) } returns profile
        coEvery { titles.load(movie.ref, null) } returns movie
        coEvery { titles.imdbIdForDetails(movie.ref) } returns "tt0000007"
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): DetailsViewModel {
        val library = LibraryRepository(libraryDao, Clock.systemUTC(), backgroundScope)
        return DetailsViewModel(profiles, titles, library, PlaybackProgressRecorder(FakeWatchProgressDao(), Clock.systemUTC()))
    }

    @Test
    fun `library button toggles between Add to library and Remove from library`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = viewModel()
        vm.open(1, movie.media)
        assertEquals("Add to library", vm.state.value.libraryLabel)

        vm.toggleLibrary()
        assertTrue(vm.state.value.inLibrary)
        assertEquals("Remove from library", vm.state.value.libraryLabel)
        assertEquals(1, libraryDao.rows.value.size)

        vm.toggleLibrary()
        assertFalse(vm.state.value.inLibrary)
        assertEquals("Add to library", vm.state.value.libraryLabel)
        assertTrue(libraryDao.rows.value.isEmpty())
    }

    @Test
    fun `a title already saved opens with Remove from library`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = viewModel()
        LibraryRepository(libraryDao, Clock.systemUTC(), backgroundScope).add(1, movie)

        vm.open(1, movie.media)

        assertTrue(vm.state.value.inLibrary)
        assertEquals("Remove from library", vm.state.value.libraryLabel)
    }

    @Test
    fun `a duplicate add press shows Already in library and stores nothing extra`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = viewModel()
        vm.open(1, movie.media)
        advanceUntilIdle()

        // Two presses before the button has had a chance to flip to "Remove from library".
        vm.toggleLibrary()
        vm.toggleLibrary()
        advanceUntilIdle()

        assertEquals(DetailsEvent.Message(DetailsViewModel.ALREADY_IN_LIBRARY), vm.events.first())
        assertEquals(1, libraryDao.rows.value.size)
        assertTrue(vm.state.value.inLibrary)
    }

    @Test
    fun `the IMDb id is looked up only once Details opens`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = viewModel()
        coVerify(exactly = 0) { titles.imdbIdForDetails(any()) }

        vm.open(1, movie.media)

        coVerify(exactly = 1) { titles.imdbIdForDetails(movie.ref) }
        val target = vm.awaitPlayTarget()
        assertEquals("tt0000007", target?.imdbId)
        assertEquals(null, target?.episode)
    }

    @Test
    fun `Shuffle starts a show-wide shuffle while Play stays sequential`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val show = testDetails(11, MediaType.TV, seasons = listOf(SeasonInfo(1, "Season 1", 4), SeasonInfo(2, "Season 2", 4)))
        coEvery { titles.load(show.ref, profile.toAgeRestriction()) } returns show
        coEvery { titles.imdbIdForDetails(show.ref) } returns "tt0000011"
        val vm = viewModel()
        vm.open(1, show.media)

        val episode = checkNotNull(vm.shuffleEpisode())
        val shuffled = vm.awaitPlayTarget(episode, ShuffleMode.Show)
        assertEquals(episode, shuffled?.episode)
        assertEquals(ShuffleSession(ShuffleMode.Show, listOf(episode)), shuffled?.shuffle)

        val play = vm.awaitPlayTarget()
        assertEquals(EpisodeRef(1, 1), play?.episode)
        assertEquals(ShuffleSession.Sequential, play?.shuffle)
    }

    @Test
    fun `a title outside a Kids profile's ceiling is shown as restricted`()= runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val kid = testProfile(2, isKids = true)
        val show = testDetails(9, MediaType.TV)
        coEvery { profiles.getProfile(2) } returns kid
        coEvery { titles.load(show.ref, kid.toAgeRestriction()) } throws RestrictedTitleException()
        val vm = viewModel()

        vm.open(2, show.media)

        assertEquals(DetailsContent.Restricted, vm.state.value.content)
        assertEquals(null, vm.awaitPlayTarget())
        coVerify(exactly = 0) { titles.imdbIdForDetails(any()) }
    }
}
