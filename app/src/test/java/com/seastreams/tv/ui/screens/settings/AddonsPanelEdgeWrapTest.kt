package com.seastreams.tv.ui.screens.settings

import androidx.compose.ui.input.key.Key
import com.seastreams.tv.ui.theme.EdgeKeyResult
import com.seastreams.tv.ui.theme.edgeKeyResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Two-way §1 edge-stop-then-wrap on the Manage addons list: addon rows `0 until addonCount`, then the
 * closing `Install more addons` row at index `addonCount`.
 */
class AddonsPanelEdgeWrapTest {

    @Test
    fun `the Install more addons row is always the last item`() {
        assertEquals(0, addonsPanelLastIndex(0))
        assertEquals(1, addonsPanelLastIndex(1))
        assertEquals(3, addonsPanelLastIndex(3))
    }

    @Test
    fun `with several addons the first row owns Up, Install more addons owns Down, the rest own nothing`() {
        assertEquals(setOf(Key.DirectionUp), addonsPanelEdgeKeys(index = 0, addonCount = 3))
        assertEquals(emptySet<Key>(), addonsPanelEdgeKeys(index = 1, addonCount = 3))
        assertEquals(emptySet<Key>(), addonsPanelEdgeKeys(index = 2, addonCount = 3))
        assertEquals(setOf(Key.DirectionDown), addonsPanelEdgeKeys(index = 3, addonCount = 3))
    }

    @Test
    fun `with one addon its row owns Up and Install more addons owns Down`() {
        assertEquals(setOf(Key.DirectionUp), addonsPanelEdgeKeys(index = 0, addonCount = 1))
        assertEquals(setOf(Key.DirectionDown), addonsPanelEdgeKeys(index = 1, addonCount = 1))
    }

    @Test
    fun `with no addons the single Install more addons row consumes both directions`() {
        val keys = addonsPanelEdgeKeys(index = 0, addonCount = 0)
        assertEquals(setOf(Key.DirectionUp, Key.DirectionDown), keys)
        listOf(0, 3).forEach { repeat ->
            assertEquals(true, edgeKeyResult(Key.DirectionUp in keys, true, repeat) != EdgeKeyResult.PASS)
            assertEquals(true, edgeKeyResult(Key.DirectionDown in keys, true, repeat) != EdgeKeyResult.PASS)
        }
    }

    @Test
    fun `fresh Up on the first addon row wraps, held Up stops there`() {
        val owns = Key.DirectionUp in addonsPanelEdgeKeys(index = 0, addonCount = 4)
        assertEquals(EdgeKeyResult.WRAP, edgeKeyResult(matchesKey = owns, isKeyDown = true, repeatCount = 0))
        assertEquals(EdgeKeyResult.HOLD, edgeKeyResult(matchesKey = owns, isKeyDown = true, repeatCount = 2))
    }

    @Test
    fun `fresh Down on Install more addons wraps, held Down stops there`() {
        val owns = Key.DirectionDown in addonsPanelEdgeKeys(index = 4, addonCount = 4)
        assertEquals(EdgeKeyResult.WRAP, edgeKeyResult(matchesKey = owns, isKeyDown = true, repeatCount = 0))
        assertEquals(EdgeKeyResult.HOLD, edgeKeyResult(matchesKey = owns, isKeyDown = true, repeatCount = 1))
    }

    @Test
    fun `inward presses on the edge rows are ordinary focus moves`() {
        val downOnFirst = Key.DirectionDown in addonsPanelEdgeKeys(index = 0, addonCount = 4)
        val upOnLast = Key.DirectionUp in addonsPanelEdgeKeys(index = 4, addonCount = 4)
        assertEquals(EdgeKeyResult.PASS, edgeKeyResult(matchesKey = downOnFirst, isKeyDown = true, repeatCount = 0))
        assertEquals(EdgeKeyResult.PASS, edgeKeyResult(matchesKey = upOnLast, isKeyDown = true, repeatCount = 0))
    }
}
