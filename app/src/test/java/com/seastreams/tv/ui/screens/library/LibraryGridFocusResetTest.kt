package com.seastreams.tv.ui.screens.library

import com.seastreams.tv.core.database.entity.LibrarySortOrder
import com.seastreams.tv.domain.usecase.LibraryFilter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A Library Sort or Genre pick scrolls to the top and focuses the first poster of the new list
 * (PRD §7); sidebar filter switches never do.
 */
class LibraryGridFocusResetTest {

    private val mark = FocusedPosterMark()
    private val allAtoZ = LibraryGridShown(LibraryFilter.ALL, LibrarySortOrder.A_TO_Z, genreId = null)

    // --- Sort picks ---

    @Test
    fun `re-picking the active sort persists nothing and waits for nothing`() {
        val reset = GridFocusReset()

        assertFalse(reset.requestSort(LibrarySortOrder.A_TO_Z, allAtoZ))
        assertFalse(reset.awaiting(allAtoZ), "the grid and focus are left alone")
    }

    @Test
    fun `a new sort waits for its own order, never the pre-sort grid, and fires once`() {
        val reset = GridFocusReset()
        val sorted = allAtoZ.copy(sort = LibrarySortOrder.LAST_ADDED)

        assertTrue(reset.requestSort(LibrarySortOrder.LAST_ADDED, allAtoZ))
        assertFalse(reset.awaiting(allAtoZ), "a stale pre-sort emission never resets the grid")
        assertTrue(reset.awaiting(sorted))
        assertFalse(reset.awaiting(sorted), "the genre-name re-emission doesn't reset it again")
    }

    @Test
    fun `the latest of two quick sort picks wins`() {
        val reset = GridFocusReset()

        reset.requestSort(LibrarySortOrder.LAST_ADDED, allAtoZ)
        reset.requestSort(LibrarySortOrder.NOT_WATCHED, allAtoZ)

        assertFalse(reset.awaiting(allAtoZ.copy(sort = LibrarySortOrder.LAST_ADDED)))
        assertTrue(reset.awaiting(allAtoZ.copy(sort = LibrarySortOrder.NOT_WATCHED)))
    }

    // --- Genre picks ---

    @Test
    fun `a new genre waits for the state showing it applied, and fires once`() {
        val reset = GridFocusReset()
        val action = allAtoZ.copy(genreId = 28)

        assertTrue(reset.requestGenre(28, allAtoZ))
        assertFalse(reset.awaiting(allAtoZ), "the unfiltered grid before the pick never resets it")
        assertTrue(reset.awaiting(action))
        assertFalse(reset.awaiting(action))
    }

    @Test
    fun `All Genres from an applied genre resets the grid`() {
        val reset = GridFocusReset()
        val action = allAtoZ.copy(genreId = 28)

        assertTrue(reset.requestGenre(null, action))
        assertFalse(reset.awaiting(action))
        assertTrue(reset.awaiting(allAtoZ))
    }

    @Test
    fun `re-picking the applied genre, or All Genres with none or a dormant genre applied, arms nothing`() {
        val reset = GridFocusReset()
        val action = allAtoZ.copy(genreId = 28)
        // Action selected but unapplied under TV Shows: the grid shows the whole filter, the pill reads All Genres.
        val dormant = LibraryGridShown(LibraryFilter.TV_SHOWS, LibrarySortOrder.A_TO_Z, genreId = null)

        assertFalse(reset.requestGenre(28, action))
        assertFalse(reset.requestGenre(null, allAtoZ))
        assertFalse(reset.requestGenre(null, dormant))
        listOf(action, allAtoZ, dormant).forEach { assertFalse(reset.awaiting(it), "focus stays on the Genre pill") }
    }

    @Test
    fun `a sort then a genre picked before the sort lands waits for both`() {
        val reset = GridFocusReset()
        val sorted = allAtoZ.copy(sort = LibrarySortOrder.Z_TO_A)

        reset.requestSort(LibrarySortOrder.Z_TO_A, allAtoZ)
        reset.requestGenre(28, allAtoZ) // the sort hasn't reached the screen yet

        assertFalse(reset.awaiting(sorted), "only half of the target")
        assertTrue(reset.awaiting(sorted.copy(genreId = 28)))
    }

    // --- Sidebar filter switches ---

    @Test
    fun `sidebar filter switches never trigger the jump`() {
        val reset = GridFocusReset()

        LibraryFilter.entries.forEach { filter ->
            assertFalse(reset.awaiting(allAtoZ.copy(filter = filter)), "$filter: focus stays on the sidebar")
        }
    }

    @Test
    fun `a pending pick is dropped when the grid shows another filter, and never fires later`() {
        val reset = GridFocusReset()
        reset.requestGenre(28, allAtoZ)

        assertFalse(reset.awaiting(LibraryGridShown(LibraryFilter.MOVIES, LibrarySortOrder.A_TO_Z, genreId = 28)))
        assertFalse(reset.awaiting(allAtoZ.copy(genreId = 28)), "switching back to All doesn't jump either")
    }

    // --- resetGridToFirst ---

    @Test
    fun `the first focus attempt comes after the dropdown settle frames`() = runTest {
        val log = mutableListOf<String>()

        val focused = resetGridToFirst(
            hasEntries = true,
            scrollToTop = { log += "scroll" },
            firstPosterReady = { true },
            focusFirst = { log += "focus"; mark.index = 0; true },
            mark = mark,
            maxFrames = 5,
            awaitFrame = { log += "frame" },
        )

        assertTrue(focused)
        assertEquals(DROPDOWN_SETTLE_FRAMES, 2)
        assertEquals(listOf("scroll", "frame", "frame", "focus"), log, "strictly after the dropdown's own pill refocus")
    }

    @Test
    fun `no focus attempt until the new list's first poster is laid out at index 0`() = runTest {
        var frame = 0
        val attemptedOnFrames = mutableListOf<Int>()

        val focused = resetGridToFirst(
            hasEntries = true,
            scrollToTop = {},
            firstPosterReady = { frame >= 4 }, // the previous layout still holds index 0 for a while
            focusFirst = { attemptedOnFrames += frame; mark.index = 0; true },
            mark = mark,
            maxFrames = 10,
            awaitFrame = { frame++ },
        )

        assertTrue(focused)
        assertEquals(listOf(4), attemptedOnFrames)
    }

    @Test
    fun `a request redirected to another poster is retried until index 0 reports focus`() = runTest {
        // The old focusRestorer bug: requesting poster 0 landed on the previously focused title, now 7th.
        val landings = ArrayDeque(listOf(7, 0))
        var attempts = 0

        val focused = resetGridToFirst(
            hasEntries = true,
            scrollToTop = {},
            firstPosterReady = { true },
            focusFirst = { attempts++; mark.index = landings.removeFirst(); true },
            mark = mark,
            maxFrames = 5,
            awaitFrame = {},
        )

        assertTrue(focused)
        assertEquals(2, attempts, "a successful-looking request that landed on 7 never ends the reset")
        assertEquals(0, mark.index)
    }

    @Test
    fun `a stale index-0 mark from before the pick is cleared and never counts`() = runTest {
        mark.index = 0 // poster 0 was focused before the user went Up to the dropdowns
        var attempts = 0

        val focused = resetGridToFirst(
            hasEntries = true,
            scrollToTop = {},
            firstPosterReady = { true },
            focusFirst = { attempts++; false }, // nothing is attached yet
            mark = mark,
            maxFrames = 3,
            awaitFrame = {},
        )

        assertFalse(focused)
        assertEquals(3, attempts)
    }

    @Test
    fun `focus confirmed a frame after the request counts without re-requesting`() = runTest {
        var attempts = 0
        var confirmNextFrame = false

        val focused = resetGridToFirst(
            hasEntries = true,
            scrollToTop = {},
            firstPosterReady = { true },
            focusFirst = { attempts++; confirmNextFrame = true; true }, // the focus event arrives late
            mark = mark,
            maxFrames = 5,
            awaitFrame = { if (confirmNextFrame) mark.index = 0 },
        )

        assertTrue(focused)
        assertEquals(1, attempts)
    }

    @Test
    fun `an empty grid is scrolled but never focused, leaving focus on the dropdown control`() = runTest {
        var scrolled = false
        var attempts = 0

        val focused = resetGridToFirst(
            hasEntries = false,
            scrollToTop = { scrolled = true },
            firstPosterReady = { true },
            focusFirst = { attempts++; true },
            mark = mark,
            maxFrames = 5,
            awaitFrame = {},
        )

        assertFalse(focused)
        assertTrue(scrolled, "a later non-empty grid starts at the top")
        assertEquals(0, attempts)
    }

    @Test
    fun `gives up after maxFrames when the first poster never attaches`() = runTest {
        var attempts = 0

        val focused = resetGridToFirst(
            hasEntries = true,
            scrollToTop = {},
            firstPosterReady = { true },
            focusFirst = { attempts++; false },
            mark = mark,
            maxFrames = 4,
            awaitFrame = {},
        )

        assertFalse(focused)
        assertEquals(4, attempts)
    }
}
