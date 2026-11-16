package com.seastreams.tv.ui.screens.catalog

import androidx.compose.ui.focus.FocusDirection
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamingProvider
import com.seastreams.tv.domain.usecase.DiscoverCatalogSources
import com.seastreams.tv.ui.components.RowEntry
import com.seastreams.tv.ui.components.RowFocusMemory
import com.seastreams.tv.ui.components.restoreIndex
import com.seastreams.tv.ui.components.rowEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * `Up`/`Down` between the catalog rows of Movies and TV Shows lands on each row's last-focused card,
 * exactly as [com.seastreams.tv.ui.components.ExpandingCatalogRow]'s `onEnter` resolves it.
 */
class DiscoverRowFocusTest {

    private val memory = RowFocusMemory()

    private fun rowIds(mediaType: MediaType): List<String> = DiscoverCatalogSources
        .rowSpecs(mediaType, listOf(StreamingProvider.entries[0], StreamingProvider.entries[1]), emptyList())
        .map { it.id }

    /** The user moves focus to card [index] of row [rowId] (the card's `onFocused`). */
    private fun focusCard(rowId: String, index: Int) {
        memory.stateFor(rowId).intValue = index
    }

    /** `Up`/`Down` into row [rowId]: the card focus lands on, then that card takes focus. */
    private fun enterRow(rowId: String, direction: FocusDirection, lastIndex: Int = ROW_LAST_INDEX): Int {
        val target = restoreIndex(memory.lastFocused(rowId), lastIndex)
        val entry = rowEntry(direction, targetVisible = true)
        assertEquals(RowEntry.FOCUS_LAST, entry)
        focusCard(rowId, target)
        return target
    }

    @ParameterizedTest
    @EnumSource(MediaType::class)
    fun `Down then Up returns to the exact card last focused in the upper row`(mediaType: MediaType) {
        val (popular, service) = rowIds(mediaType)
        focusCard(popular, 7)

        assertEquals(0, enterRow(service, FocusDirection.Down), "a row never visited starts at card 1")
        focusCard(service, 3)
        assertEquals(7, enterRow(popular, FocusDirection.Up))
        assertEquals(3, enterRow(service, FocusDirection.Down))
    }

    @ParameterizedTest
    @EnumSource(MediaType::class)
    fun `repeated Down and Up never drift across any row`(mediaType: MediaType) {
        val ids = rowIds(mediaType)
        ids.forEachIndexed { row, id -> focusCard(id, row * 4 + 2) }

        repeat(5) {
            ids.drop(1).forEach { enterRow(it, FocusDirection.Down) }
            ids.reversed().drop(1).forEach { enterRow(it, FocusDirection.Up) }
        }

        assertEquals(ids.indices.map { it * 4 + 2 }, ids.map { memory.lastFocused(it) })
    }

    @ParameterizedTest
    @EnumSource(MediaType::class)
    fun `Up from the Streaming services bar lands on the bottom row's last-focused card`(mediaType: MediaType) {
        val bottomRow = rowIds(mediaType).last()
        focusCard(bottomRow, 12)

        assertEquals(12, enterRow(bottomRow, FocusDirection.Up))
    }

    @Test
    fun `Down into the Streaming services bar still lands on its first chip`() {
        assertEquals(ProviderBarEntry.FOCUS_FIRST, providerBarEntry(focusable = true, fromAbove = true, firstChipVisible = true))
    }

    @Test
    fun `rows of the Movies and TV Shows tabs are remembered separately`() {
        val movies = rowIds(MediaType.MOVIE).first()
        val shows = rowIds(MediaType.TV).first()
        focusCard(movies, 9)

        assertEquals(0, memory.lastFocused(shows))
        assertEquals(9, memory.lastFocused(movies))
    }

    private companion object {
        const val ROW_LAST_INDEX = 20
    }
}
