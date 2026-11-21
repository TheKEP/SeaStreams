package com.seastreams.tv.ui.screens.details

import com.seastreams.tv.core.database.entity.WatchProgressEntity
import com.seastreams.tv.data.details.RestrictedTitleException
import com.seastreams.tv.data.details.TitleDetailsRepository
import com.seastreams.tv.data.library.LibraryRepository
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.domain.playback.PlaybackProgressRecorder
import com.seastreams.tv.domain.playback.ProgressPoint
import com.seastreams.tv.domain.playback.ShuffleMode
import com.seastreams.tv.domain.playback.ShuffleSession
import com.seastreams.tv.testutil.FakeLibraryDao
import com.seastreams.tv.testutil.FakeWatchProgressDao
import com.seastreams.tv.testutil.testDetails
import com.seastreams.tv.testutil.testProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private val progressDao = FakeWatchProgressDao()
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
        return DetailsViewModel(profiles, titles, library, PlaybackProgressRecorder(progressDao, Clock.systemUTC()))
    }

    private suspend fun seedProgress(tmdbId: Int, type: MediaType, positionMs: Long, season: Int = 0, episode: Int = 0) =
        progressDao.upsert(
            WatchProgressEntity(
                profileId = 1, tmdbId = tmdbId, mediaType = type, seasonNumber = season, episodeNumber = episode,
                title = "Title $tmdbId", episodeTitle = null, posterPath = null, backdropPath = null,
                positionMs = positionMs, durationMs = 100_000, updatedAtMillis = 1,
            ),
        )

    @Test
    fun `Clear progress on a part-watched movie deletes its record and turns Resume into Play`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedProgress(movie.media.tmdbId, MediaType.MOVIE, positionMs = 40_000)
        val vm = viewModel()
        vm.open(1, movie.media)
        assertEquals("Resume", vm.state.value.playLabel)
        assertTrue(vm.state.value.canClearProgress)

        vm.clearProgress()

        assertTrue(progressDao.rows.value.isEmpty())
        assertEquals("Play", vm.state.value.playLabel)
        assertFalse(vm.state.value.canClearProgress)
    }

    @Test
    fun `Clear progress flips Resume to Play synchronously, before the delete lands`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        seedProgress(movie.media.tmdbId, MediaType.MOVIE, positionMs = 40_000)
        val vm = viewModel()
        vm.open(1, movie.media)
        advanceUntilIdle()
        assertEquals("Resume", vm.state.value.playLabel)

        vm.clearProgress()
        // Same frame as the focus move to Play: no coroutine has run yet.
        assertEquals("Play", vm.state.value.playLabel)
        assertFalse(vm.state.value.canClearProgress)
        assertEquals("", vm.state.value.resumeNoteSlot)
        assertEquals(1, progressDao.rows.value.size)

        val immediate = vm.state.value
        advanceUntilIdle()
        assertTrue(progressDao.rows.value.isEmpty())
        assertEquals(immediate, vm.state.value)
    }

    /** Every state [vm] emits from now on, recorded without conflation. */
    private fun TestScope.recordStates(vm: DetailsViewModel): List<DetailsUiState> {
        val seen = mutableListOf<DetailsUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { seen += it } }
        return seen
    }

    private fun assertNoPlayFlash(states: List<DetailsUiState>, expectedLabel: String) {
        val ready = states.filter { it.actionsReady }
        assertTrue(ready.isNotEmpty(), "never became ready")
        ready.forEach { state ->
            assertEquals(expectedLabel, state.playLabel)
            assertTrue(state.resumeNoteSlot.endsWith(" left"), "slot=${state.resumeNoteSlot}")
        }
    }

    @Test
    fun `a show with S1E5 in progress is first drawn as Resume S1E5, never Play`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val show = testDetails(11, MediaType.TV, seasons = listOf(SeasonInfo(1, "Season 1", 10)))
        coEvery { titles.load(show.ref, profile.toAgeRestriction()) } returns show
        coEvery { titles.imdbIdForDetails(show.ref) } returns "tt0000011"
        seedProgress(show.media.tmdbId, MediaType.TV, positionMs = 40_000, season = 1, episode = 5)
        val vm = viewModel()
        val states = recordStates(vm)

        vm.open(1, show.media)
        advanceUntilIdle()

        assertNoPlayFlash(states, "Resume S1:E5")
    }

    @Test
    fun `a part-watched movie is first drawn as Resume, never Play`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        seedProgress(movie.media.tmdbId, MediaType.MOVIE, positionMs = 40_000)
        val vm = viewModel()
        val states = recordStates(vm)

        vm.open(1, movie.media)
        advanceUntilIdle()

        assertNoPlayFlash(states, "Resume")
    }

    @Test
    fun `unwatched and completed movies offer no Clear progress`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = viewModel()
        vm.open(1, movie.media)
        assertFalse(vm.state.value.canClearProgress)

        seedProgress(movie.media.tmdbId, MediaType.MOVIE, positionMs = 95_000)
        assertEquals("Play", vm.state.value.playLabel)
        assertFalse(vm.state.value.canClearProgress)
        vm.clearProgress()
        assertEquals(1, progressDao.rows.value.size)
    }

    @Test
    fun `a show with a part-watched episode offers no Clear progress and keeps its row`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val show = testDetails(11, MediaType.TV, seasons = listOf(SeasonInfo(1, "Season 1", 4)))
        coEvery { titles.load(show.ref, profile.toAgeRestriction()) } returns show
        coEvery { titles.imdbIdForDetails(show.ref) } returns "tt0000011"
        seedProgress(show.media.tmdbId, MediaType.TV, positionMs = 40_000, season = 1, episode = 1)
        val vm = viewModel()
        vm.open(1, show.media)

        assertFalse(vm.state.value.canClearProgress)
        vm.clearProgress()
        assertEquals(1, progressDao.rows.value.size)
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
    fun `Resume waits for saved progress so a show resumes its in-progress episode, not S1E1`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val show = testDetails(11, MediaType.TV, seasons = listOf(SeasonInfo(1, "Season 1", 4), SeasonInfo(2, "Season 2", 4)))
        coEvery { titles.load(show.ref, profile.toAgeRestriction()) } returns show
        coEvery { titles.imdbIdForDetails(show.ref) } returns "tt0000011"
        // Progress arrives only after the title and its IMDb id (e.g. a slow first Room emission).
        val progressFlow = MutableSharedFlow<List<ProgressPoint>>()
        val recorder = mockk<PlaybackProgressRecorder>()
        every { recorder.observe(1, show.ref) } returns progressFlow
        val library = LibraryRepository(libraryDao, Clock.systemUTC(), backgroundScope)
        val vm = DetailsViewModel(profiles, titles, library, recorder)
        vm.open(1, show.media)
        assertTrue(vm.state.value.imdbResolved)

        val target = async { vm.awaitPlayTarget() }
        assertFalse(target.isCompleted)
        progressFlow.emit(listOf(ProgressPoint(EpisodeRef(2, 4), positionMs = 40_000, durationMs = 100_000, updatedAtMillis = 5)))

        assertEquals(EpisodeRef(2, 4), target.await()?.episode)
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
