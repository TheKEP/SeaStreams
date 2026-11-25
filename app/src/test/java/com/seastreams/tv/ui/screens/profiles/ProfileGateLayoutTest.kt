package com.seastreams.tv.ui.screens.profiles

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.seastreams.tv.ui.theme.verticalEdgeKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `Choose a Profile` on the 960 × 540 dp TV viewport (PRD §2): every profile row and the `+` tile fit, from one profile
 * up to the five-profile cap, and the column follows the two-way edge-stop-then-wrap rule.
 */
class ProfileGateLayoutTest {

    /** 540 dp minus the screen's chrome: 28 top + 16 bottom padding, the title and its 12 dp gap (~24 + 12). */
    private val available = 540.dp - 28.dp - 16.dp - 36.dp

    /** Rows for [profiles] profiles: `+` is shown below the five-profile cap. */
    private fun slots(profiles: Int) = profiles + if (profiles < 5) 1 else 0

    @Test
    fun `one to five profiles and the plus tile always fit the viewport`() {
        (1..5).forEach { profiles ->
            val count = slots(profiles)
            val metrics = profileGateMetrics(count, available)
            assertTrue(metrics.columnHeight(count) <= available, "$profiles profiles: ${metrics.columnHeight(count)} > $available")
            // The focused, scaled avatar never spills over its row.
            assertTrue(metrics.slot >= metrics.focusedTile * PROFILE_TILE_FOCUS_SCALE - 0.01.dp)
            assertTrue(metrics.focusedTile > metrics.tile)
        }
    }

    @Test
    fun `four profiles plus the add tile - the reported overflow - fit with room to spare`() {
        val metrics = profileGateMetrics(5, available)
        assertTrue(metrics.columnHeight(5) <= available)
        assertTrue(metrics.tile >= MinTile)
        assertEquals(MinSpacing, metrics.spacing)
    }

    @Test
    fun `few profiles keep the full-size avatars, larger counts shrink them together`() {
        val two = profileGateMetrics(slots(2), available)
        assertEquals(FullTile, two.tile)
        assertEquals(FullFocusedTile, two.focusedTile)
        assertEquals(FullSpacing, two.spacing)
        val five = profileGateMetrics(slots(5), available)
        assertTrue(five.tile < FullTile && five.focusedTile < FullFocusedTile)
        assertTrue(five.tile >= MinTile && five.focusedTile >= MinFocusedTile)
    }

    @Test
    fun `sizes only shrink as rows are added`() {
        val tiles = (1..6).map { profileGateMetrics(it, available).focusedTile }
        tiles.zipWithNext().forEach { (fewer, more) -> assertTrue(more <= fewer) }
    }

    @Test
    fun `Up on the first row and Down on the last row own the wrap, for every profile count`() {
        (1..5).forEach { profiles ->
            val last = slots(profiles) - 1
            assertTrue(Key.DirectionUp in verticalEdgeKeys(0, last))
            assertTrue(Key.DirectionDown in verticalEdgeKeys(last, last))
            (1 until last).forEach { middle -> assertEquals(emptySet<Key>(), verticalEdgeKeys(middle, last)) }
        }
        // Five profiles: no `+`, so profile 5 is the last row.
        assertEquals(setOf(Key.DirectionDown), verticalEdgeKeys(4, slots(5) - 1))
    }
}
