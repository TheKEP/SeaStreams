package com.seastreams.tv.ui.screens.profiles

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import kotlin.math.floor

/**
 * Sizes of the `Choose a Profile` column (PRD §2): [tile] is a resting avatar, [focusedTile] the focused one,
 * [slot] the square each row reserves (the focused tile scaled by the surface's focus scale, so a focused avatar
 * never overlaps its neighbours) and [spacing] the gap between rows.
 */
data class ProfileGateMetrics(val tile: Dp, val focusedTile: Dp, val slot: Dp, val spacing: Dp) {
    /** Height of [slotCount] rows with their gaps. */
    fun columnHeight(slotCount: Int): Dp = if (slotCount <= 0) 0.dp else slot * slotCount + spacing * (slotCount - 1)
}

/** The surface's focused scale on a profile avatar. */
const val PROFILE_TILE_FOCUS_SCALE = 1.08f

internal val FullTile = 76.dp
internal val FullFocusedTile = 108.dp
internal val MinTile = 48.dp
internal val MinFocusedTile = 56.dp
internal val FullSpacing = 18.dp
internal val MinSpacing = 8.dp

/**
 * The largest metrics that fit [slotCount] rows (profiles plus the `+` tile, up to 6) into [availableHeight]:
 * today's 76 / 108 dp avatars and 18 dp gaps while they fit (one to three profiles), otherwise gaps shrink to 8 dp
 * and the avatars scale down together (resting / focused keep their ratio), never below 48 / 56 dp.
 */
fun profileGateMetrics(slotCount: Int, availableHeight: Dp): ProfileGateMetrics {
    val full = metricsFor(FullFocusedTile, FullSpacing)
    val count = slotCount.coerceAtLeast(1)
    if (full.columnHeight(count) <= availableHeight) return full
    val spacing = MinSpacing
    // slot = focused * scale, and count slots + (count - 1) gaps must fit.
    // Whole dp, rounded down, so float rounding can never push the column past the available height.
    val slot = (availableHeight - spacing * (count - 1)) / count
    val fitting = floor((slot / PROFILE_TILE_FOCUS_SCALE).value).dp
    val focused = max(MinFocusedTile, min(FullFocusedTile, fitting))
    return metricsFor(focused, spacing)
}

private fun metricsFor(focusedTile: Dp, spacing: Dp): ProfileGateMetrics {
    val tile = max(MinTile, focusedTile * (FullTile / FullFocusedTile))
    return ProfileGateMetrics(tile = tile, focusedTile = focusedTile, slot = focusedTile * PROFILE_TILE_FOCUS_SCALE, spacing = spacing)
}
