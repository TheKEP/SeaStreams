package com.seastreams.tv.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Fixed-slot catalog row geometry (PRD §4): a stationary 16:9 showcase slot pinned at the left gutter
 * and, beside it, a strip of uniform 2:3 posters that never change width. The focused poster `i` is
 * scrolled to sit underneath the slot (laid out and focusable, but not drawn), so the next poster
 * `i + 1` always sits right next to the slot and no poster ever shows to its left.
 */

/** Gap between strip posters, and between the slot and the first visible poster. */
internal val CardSpacing = 12.dp

/** One strip step: a poster plus its gap. */
internal val PosterPitch: Dp = PosterWidth + CardSpacing

/**
 * Strip content-start inset (from the slot's left edge): a poster at the content start ends exactly
 * at the slot's right edge, so the following poster starts at `slot + CardSpacing`.
 */
internal val StripLead: Dp = ExpandedCardWidth - PosterWidth

/**
 * Catalog row horizontal glide time (PRD §4 Catalog Row Scroll Speed). Shorter than the held-key step
 * interval (`HORIZONTAL_REPEAT_INTERVAL_MS`), so each step settles before the next held tick fires.
 */
const val CATALOG_ROW_GLIDE_MS = 120

/** Showcase slot backdrop/logo crossfade time when the focused poster changes. */
const val SLOT_CROSSFADE_MS = 120

/** [slotIndex] for an empty row: the slot has no item to show. */
const val NO_SLOT_ITEM = -1

/**
 * The strip item to scroll to the content start (underneath the slot). While `View More` (index
 * [itemCount]) holds focus the last media item goes under the slot, so `View More` itself sits
 * visibly next to it.
 */
fun stripScrollIndex(focusedIndex: Int, itemCount: Int, viewMoreFocused: Boolean): Int = when {
    itemCount <= 0 -> 0
    viewMoreFocused -> itemCount - 1
    else -> focusedIndex.coerceIn(0, itemCount - 1)
}

/**
 * The media item the showcase slot shows: the row's last-focused item (`0` by default), whether or not
 * the row holds focus. A remembered `View More` position falls back to the last media item, so the
 * slot is never blank; [NO_SLOT_ITEM] only for an empty row.
 */
fun slotIndex(lastFocused: Int, itemCount: Int): Int =
    if (itemCount <= 0) NO_SLOT_ITEM else lastFocused.coerceIn(0, itemCount - 1)

/** The slot draws the focus ring only while a media poster of its row holds focus (not `View More`). */
fun slotFocusRing(rowFocused: Boolean, viewMoreFocused: Boolean): Boolean = rowFocused && !viewMoreFocused

/** Left edge of strip item [index], from the slot's left edge, while item [scrollIndex] is at the content start. */
fun posterStartX(index: Int, scrollIndex: Int): Dp = StripLead + PosterPitch * (index - scrollIndex)
