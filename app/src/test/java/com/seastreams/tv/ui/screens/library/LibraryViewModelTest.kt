package com.seastreams.tv.ui.screens.library

import com.seastreams.tv.core.database.entity.LibrarySortOrder
import com.seastreams.tv.data.catalog.GenreRepository
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.usecase.GetLibraryUseCase
import com.seastreams.tv.domain.usecase.LibraryFilter
import com.seastreams.tv.testutil.testProfile
import com.seastreams.tv.ui.components.NavTab
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Library filter switching (PRD §7): instant sidebar, settled grid, derivation off the main thread, and a
 * Genre filter that persists across filters but resets on leaving the page.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val main = StandardTestDispatcher()
    private val profiles = mockk<ProfileRepository>()
    private val library = mockk<GetLibraryUseCase>()
    private val genres = mockk<GenreRepository>()
    private val profile = testProfile(1, isKids = false).copy(librarySortOrder = LibrarySortOrder.Z_TO_A)

    private val entries = listOf(
        libraryEntry(1, "Alien", MediaType.MOVIE, genres = listOf(28)),
        libraryEntry(2, "Bluey", MediaType.TV, genres = listOf(16)),
        libraryEntry(3, "Mad Max", MediaType.MOVIE, genres = listOf(28, 878)),
        libraryEntry(4, "Zoey 101", MediaType.TV, genres = listOf(35)),
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(main)
        every { profiles.observeProfile(profile.id) } returns flowOf(profile)
        every { library.observe(profile) } returns flowOf(entries)
        coEvery { genres.allNames() } returns mapOf(28 to "Action", 16 to "Animation", 35 to "Comedy")
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(derive: CoroutineDispatcher = main) = LibraryViewModel(profiles, library, genres, derive)

    /** Opens the profile, collects the UI state and records the grid's (filter, genre) on each emission. */
    private fun TestScope.opened(vm: LibraryViewModel): MutableList<LibrarySelection> {
        val grids = mutableListOf<LibrarySelection>()
        vm.open(profile.id)
        backgroundScope.launch {
            vm.uiState.collect { if (!it.isLoading) grids += LibrarySelection(it.filter, it.genreId) }
        }
        advanceUntilIdle()
        return grids
    }

    /** Lets a pending filter switch settle and the grid follow. */
    private fun TestScope.settle() {
        advanceTimeBy(FILTER_SETTLE_MILLIS + 1)
        runCurrent()
    }

    private fun titles(vm: LibraryViewModel) = vm.uiState.value.view.entries.map { it.media.title }

    @Test
    fun `filtering, sorting and indexing run on the derive dispatcher, not Main`() = runTest(main) {
        val derive = StandardTestDispatcher(TestCoroutineScheduler()) // its own scheduler: runs only when advanced
        val vm = viewModel(derive)
        opened(vm)

        assertTrue(vm.uiState.value.isLoading, "Main alone must not derive the Library")

        repeat(3) {
            derive.scheduler.advanceUntilIdle()
            advanceUntilIdle()
        }
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(listOf("Zoey 101", "Mad Max", "Bluey", "Alien"), titles(vm)) // profile's saved Z-A
    }

    @Test
    fun `the sidebar follows focus at once and the grid only after the settle delay`() = runTest(main) {
        val vm = viewModel()
        opened(vm)

        vm.selectFilter(LibraryFilter.MOVIES)
        assertEquals(LibraryFilter.MOVIES, vm.sidebarFilter.value)
        advanceTimeBy(FILTER_SETTLE_MILLIS - 1)
        runCurrent()
        assertEquals(LibraryFilter.ALL, vm.uiState.value.filter)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(LibraryFilter.MOVIES, vm.uiState.value.filter)
        assertEquals(listOf("Mad Max", "Alien"), titles(vm))
        assertEquals(mapOf('M' to 0, 'A' to 1), vm.uiState.value.view.letterIndex)
        assertEquals(listOf("Action"), vm.uiState.value.view.genreOptions.map { it.name })
    }

    @Test
    fun `filters passed through quickly never reach the grid`() = runTest(main) {
        val vm = viewModel()
        val grids = opened(vm)

        vm.selectFilter(LibraryFilter.MOVIES)
        advanceTimeBy(FILTER_SETTLE_MILLIS / 2)
        vm.selectFilter(LibraryFilter.TV_SHOWS)
        settle()

        assertEquals(listOf(LibraryFilter.ALL, LibraryFilter.TV_SHOWS), grids.map { it.filter }.distinct())
        assertEquals(listOf("Zoey 101", "Bluey"), titles(vm))
    }

    @Test
    fun `coming back to the shown filter before it settles leaves the grid alone`() = runTest(main) {
        val vm = viewModel()
        val grids = opened(vm)

        vm.selectFilter(LibraryFilter.MOVIES)
        advanceTimeBy(FILTER_SETTLE_MILLIS / 2)
        vm.selectFilter(LibraryFilter.ALL)
        settle()

        assertFalse(vm.commitPendingFilter())
        assertTrue(grids.all { it.filter == LibraryFilter.ALL })
    }

    @Test
    fun `committing applies a pending filter at once, and only once`() = runTest(main) {
        val vm = viewModel()
        opened(vm)

        vm.selectFilter(LibraryFilter.TV_SHOWS)
        assertTrue(vm.commitPendingFilter())
        runCurrent()
        assertEquals(LibraryFilter.TV_SHOWS, vm.uiState.value.filter)
        assertFalse(vm.commitPendingFilter())
    }

    @Test
    fun `the genre persists across All, Movies and TV Shows`() = runTest(main) {
        // Animation (16) is on both a movie and a show.
        every { library.observe(profile) } returns flowOf(entries + libraryEntry(5, "Toy Story", MediaType.MOVIE, genres = listOf(16)))
        val vm = viewModel()
        val grids = opened(vm)

        vm.selectGenre(16)
        runCurrent()
        assertEquals(listOf("Toy Story", "Bluey"), titles(vm))

        listOf(
            LibraryFilter.MOVIES to listOf("Toy Story"),
            LibraryFilter.TV_SHOWS to listOf("Bluey"),
            LibraryFilter.ALL to listOf("Toy Story", "Bluey"),
        ).forEach { (filter, expected) ->
            grids.clear()
            vm.selectFilter(filter)
            settle()

            assertEquals(listOf(LibrarySelection(filter, 16)), grids, "one emission, genre kept: $filter")
            assertEquals(expected, titles(vm), "$filter")
            assertEquals(16, vm.uiState.value.view.appliedGenreId)
        }
    }

    @Test
    fun `a genre with no titles under the new filter is kept but not applied`() = runTest(main) {
        val vm = viewModel()
        opened(vm)
        vm.selectGenre(28) // Action: movies only
        runCurrent()
        assertEquals(listOf("Mad Max", "Alien"), titles(vm))
        assertEquals(listOf("Action", "Animation", "Comedy"), vm.uiState.value.view.genreOptions.map { it.name })

        vm.selectFilter(LibraryFilter.TV_SHOWS)
        settle()
        val tv = vm.uiState.value
        assertEquals(28, tv.genreId, "still selected")
        assertEquals(null, tv.view.appliedGenreId, "but not applied: the dropdown reads All Genres")
        assertEquals(listOf("Zoey 101", "Bluey"), titles(vm), "the whole filter, never an empty grid")
        assertEquals(listOf("Animation", "Comedy"), tv.view.genreOptions.map { it.name })

        vm.selectFilter(LibraryFilter.MOVIES)
        settle()
        assertEquals(28, vm.uiState.value.view.appliedGenreId, "applies again where it has titles")
        assertEquals(listOf("Mad Max", "Alien"), titles(vm))

        vm.selectFilter(LibraryFilter.ALL)
        settle()
        assertEquals(28, vm.uiState.value.view.appliedGenreId)
        assertEquals(listOf("Mad Max", "Alien"), titles(vm))
    }

    @Test
    fun `committing a pending filter keeps the genre`() = runTest(main) {
        val vm = viewModel()
        opened(vm)
        vm.selectGenre(28)
        vm.selectFilter(LibraryFilter.MOVIES)

        assertTrue(vm.commitPendingFilter())
        runCurrent()

        assertEquals(LibrarySelection(LibraryFilter.MOVIES, 28), LibrarySelection(vm.uiState.value.filter, vm.uiState.value.genreId))
        assertEquals(listOf("Mad Max", "Alien"), titles(vm))
    }

    @Test
    fun `choosing All Genres while the genre is not applied clears it`() = runTest(main) {
        val vm = viewModel()
        opened(vm)
        vm.selectGenre(28)
        vm.selectFilter(LibraryFilter.TV_SHOWS)
        settle()

        vm.selectGenre(null)
        vm.selectFilter(LibraryFilter.MOVIES)
        settle()

        assertEquals(null, vm.uiState.value.genreId)
        assertEquals(listOf("Mad Max", "Alien"), titles(vm))
        assertEquals(null, vm.uiState.value.view.appliedGenreId)
    }

    @Test
    fun `a sort change keeps the applied genre while genre names reload`() = runTest(main) {
        val profileFlow = MutableStateFlow(profile)
        every { profiles.observeProfile(profile.id) } returns profileFlow
        every { library.observe(any()) } returns flowOf(entries)
        val vm = viewModel()
        opened(vm)
        vm.selectGenre(28)
        runCurrent()

        val views = mutableListOf<LibraryView>()
        backgroundScope.launch { vm.uiState.collect { if (!it.isLoading) views += it.view } }
        runCurrent()
        profileFlow.value = profile.copy(librarySortOrder = LibrarySortOrder.A_TO_Z)
        advanceUntilIdle()

        assertEquals(LibrarySortOrder.A_TO_Z, vm.uiState.value.sort)
        assertTrue(views.size > 1, "the sort change re-derived the views")
        assertTrue(views.all { it.appliedGenreId == 28 }, "matched by genre id, not by its (reloading) name")
        assertEquals(listOf("Alien", "Mad Max"), titles(vm))
    }

    @Test
    fun `leaving Library from the nav bar resets the genre`() = runTest(main) {
        val vm = viewModel()
        opened(vm)
        vm.selectFilter(LibraryFilter.MOVIES)
        settle()
        vm.selectGenre(28)

        vm.onTabSelected(NavTab.LIBRARY) // staying on the page
        runCurrent()
        assertEquals(28, vm.uiState.value.genreId)

        vm.onTabSelected(NavTab.SETTINGS) // covers the page without disposing it
        runCurrent()
        assertEquals(LibrarySelection(LibraryFilter.MOVIES, null), LibrarySelection(vm.uiState.value.filter, vm.uiState.value.genreId))
        assertEquals(listOf("Mad Max", "Alien"), titles(vm))

        vm.selectGenre(28)
        vm.onTabSelected(NavTab.HOME)
        runCurrent()
        assertEquals(null, vm.uiState.value.genreId)
    }

    @Test
    fun `a genre picked while a filter is settling applies to that filter`() = runTest(main) {
        val vm = viewModel()
        opened(vm)

        vm.selectFilter(LibraryFilter.TV_SHOWS)
        vm.selectGenre(35)
        runCurrent()

        assertEquals(LibrarySelection(LibraryFilter.TV_SHOWS, 35), LibrarySelection(vm.uiState.value.filter, vm.uiState.value.genreId))
        assertEquals(listOf("Zoey 101"), titles(vm))
    }

    @Test
    fun `returning to Library starts on All with every genre and drops a pending switch`() = runTest(main) {
        val vm = viewModel()
        opened(vm)
        vm.selectFilter(LibraryFilter.MOVIES)
        settle()
        vm.selectGenre(28)
        runCurrent()
        assertEquals(LibrarySelection(LibraryFilter.MOVIES, 28), LibrarySelection(vm.uiState.value.filter, vm.uiState.value.genreId))
        vm.selectFilter(LibraryFilter.TV_SHOWS)

        vm.open(profile.id) // the screen re-entering composition after another tab, Search or a profile switch
        settle()

        assertEquals(LibraryFilter.ALL, vm.sidebarFilter.value)
        assertEquals(LibrarySelection(), LibrarySelection(vm.uiState.value.filter, vm.uiState.value.genreId))
        assertEquals(4, vm.uiState.value.view.entries.size)
    }
}
