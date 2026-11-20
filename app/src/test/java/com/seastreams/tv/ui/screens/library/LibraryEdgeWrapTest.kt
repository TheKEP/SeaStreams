package com.seastreams.tv.ui.screens.library

import com.seastreams.tv.domain.usecase.LibraryFilter
import com.seastreams.tv.domain.usecase.LibraryQuery
import com.seastreams.tv.ui.theme.EdgeKeyResult
import com.seastreams.tv.ui.theme.edgeKeyResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Library filter sidebar ⇄ A–Z quick-jump bar, two-way §1 edge-stop-then-wrap (PRD §7). */
class LibraryEdgeWrapTest {

    private val memory = LibraryWrapMemory()

    @Test
    fun `fresh Left on a filter and fresh Right on a letter wrap`() {
        assertEquals(EdgeKeyResult.WRAP, edgeKeyResult(matchesKey = true, isKeyDown = true, repeatCount = 0))
    }

    @Test
    fun `held Left on a filter and held Right on a letter stop on the edge`() {
        assertEquals(EdgeKeyResult.HOLD, edgeKeyResult(matchesKey = true, isKeyDown = true, repeatCount = 1))
        assertEquals(EdgeKeyResult.HOLD, edgeKeyResult(matchesKey = true, isKeyDown = true, repeatCount = 12))
    }

    @Test
    fun `the release of the wrapping press does nothing`() {
        assertEquals(EdgeKeyResult.PASS, edgeKeyResult(matchesKey = true, isKeyDown = false, repeatCount = 0))
    }

    @Test
    fun `Right from the bar defaults to the selected filter`() {
        assertEquals(LibraryFilter.ALL, memory.filterTarget(selected = LibraryFilter.ALL))
        assertEquals(LibraryFilter.TV_SHOWS, memory.filterTarget(selected = LibraryFilter.TV_SHOWS))
    }

    @Test
    fun `Right from the bar restores exactly the last focused filter`() {
        LibraryFilter.entries.forEach { filter ->
            memory.onFilterFocused(filter)
            assertEquals(filter, memory.filterTarget(selected = LibraryFilter.ALL), filter.name)
        }
        memory.onFilterFocused(LibraryFilter.MOVIES)
        assertEquals(LibraryFilter.MOVIES, memory.filterTarget(selected = LibraryFilter.ALL))
    }

    @Test
    fun `Left from a filter lands on # until a letter has been focused`() {
        assertEquals(LibraryQuery.LETTERS.indexOf('#'), memory.letterTarget())
    }

    @Test
    fun `Left from a filter lands on the last focused letter`() {
        val m = LibraryQuery.LETTERS.indexOf('M')
        memory.onLetterFocused(m)
        assertEquals(m, memory.letterTarget())
        memory.onLetterFocused(LibraryQuery.LETTERS.lastIndex)
        assertEquals(LibraryQuery.LETTERS.indexOf('Z'), memory.letterTarget())
    }

    @Test
    fun `out-of-range letter indices are ignored`() {
        memory.onLetterFocused(4)
        memory.onLetterFocused(-1)
        memory.onLetterFocused(LibraryQuery.LETTERS.size)
        assertEquals(4, memory.letterTarget())
    }
}
