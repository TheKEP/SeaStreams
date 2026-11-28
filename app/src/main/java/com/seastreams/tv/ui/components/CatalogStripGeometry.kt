package com.seastreams.tv.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Fixed-slot catalog row geometry (PRD §4): a stationary 16:9 showcase slot pinned at the left gutter
 * and, beside it, a strip of uniform 2:3 posters that never change width. The focused poster `i` is
 * scrolled to sit underneath the slot (laid out and focusable, but not drawn), so the next poster
 * `i + 1` always sits right next to the slot. Left of the slot, [PreviousPosterPeek] shows a sliver of
 * poster `i - 1` bleeding off the screen edge ([PeekVisibleWidth]), a virtual continuation of the strip.
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
 * Catalog row strip glide (PRD §4 Catalog Row Scroll Speed, tuned against `desired_animation.MOV`): a
 * virtual target advances one poster per [CATALOG_ROW_RAMP_MS] and the strip follows it critically
 * damped with [CATALOG_ROW_SMOOTH_TIME_MS] ([glideFrame]). A tap settles in ~280ms; a held key (paced at
 * `HORIZONTAL_REPEAT_INTERVAL_MS`) flows at a constant speed with no stop at each poster.
 */
const val CATALOG_ROW_RAMP_MS = 160f

/** Smooth time of the critically damped follow behind [CATALOG_ROW_RAMP_MS]. */
const val CATALOG_ROW_SMOOTH_TIME_MS = 45f

/** Showcase slot backdrop/logo crossfade time when the focused poster changes. */
const val SLOT_CROSSFADE_MS = 160

/** How much of the previous poster (`i - 1`) peeks in from the left screen edge, beside the slot. */
internal val PeekVisibleWidth: Dp = RowGutter - CardSpacing

/** Left edge of the previous poster at rest: only its last [PeekVisibleWidth] is on screen. */
internal val PeekRestX: Dp = PeekVisibleWidth - PosterWidth

/** The peek layer is clipped here, a [CardSpacing] gap left of the slot, so it never touches the slot or its ring. */
internal val PeekClipRight: Dp = PeekVisibleWidth

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

/**
 * The strip's fractional scroll position: the (fractional) item index at the content start, i.e. under
 * the slot's right-aligned poster position. Strip items share one [pitchPx], so the offset maps linearly.
 */
fun stripPosition(firstIndex: Int, firstOffsetPx: Int, pitchPx: Float): Float =
    if (pitchPx <= 0f) firstIndex.toFloat() else firstIndex + firstOffsetPx / pitchPx

/**
 * The media items [PreviousPosterPeek] may draw while the strip's whole position is [base]
 * (`floor(stripPosition)`): the previous poster and the one sliding out from behind the slot.
 * Empty for an empty row; never negative indices.
 */
fun peekItems(base: Int, itemCount: Int): IntRange {
    if (itemCount <= 0 || base < 0) return IntRange.EMPTY
    return (base - 1).coerceAtLeast(0)..minOf(base, itemCount - 1)
}

/**
 * Left edge (from the screen's left edge) of peek poster [index] at strip [position]. At rest on item
 * `i`, poster `i - 1` sits at [PeekRestX] and ends [PeekVisibleWidth] in; mid-glide each peek poster
 * moves one [PosterPitch] per item, so poster `i` slides out from behind the slot as `i - 1` leaves.
 */
fun peekPosterX(index: Int, position: Float): Dp = PeekRestX + PosterPitch * (index + 1 - position)
