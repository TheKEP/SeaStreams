package com.seastreams.tv.ui.screens.catalog

import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.ui.theme.awaitNextFrame
import com.seastreams.tv.ui.theme.retryEachFrame

/**
 * The row a fresh `Down` on the bottom `Streaming services` bar wraps to (PRD §1 edge-stop-then-wrap):
 * the first catalog row with cards, i.e. `Popular Movies` / `Popular Shows` (index `0`). Only when
 * `Popular` failed or is still loading does it fall to the next loaded row. `null` (no row has
 * cards) means the press is consumed and focus stays on the bar. The nav bar is never a target.
 */
internal fun bottomWrapTargetRow(rows: List<RowContent>): Int? =
    rows.indexOfFirst { it is RowContent.Loaded && it.row.items.isNotEmpty() }.takeIf { it >= 0 }

/**
 * Wraps focus from the bottom bar to catalog row [targetRow]. The row was usually scrolled off and
 * disposed by the lazy column, so a `requestFocus()` issued before it is composed and attached
 * misses (formerly falling back to the nav bar). Instead: scroll to `(targetRow, 0)` first, then
 * each frame, once the row is laid out ([isRowLaidOut]), try [focusRow] (its active card); a slow
 * frame just means another try on the next frame. Returns whether focus landed on the row.
 */
internal suspend fun wrapBottomBarToRow(
    targetRow: Int,
    scrollTo: suspend (index: Int, offset: Int) -> Unit,
    isRowLaidOut: (index: Int) -> Boolean,
    focusRow: () -> Boolean,
    awaitFrame: suspend () -> Unit = ::awaitNextFrame,
    maxFrames: Int = WRAP_FOCUS_MAX_FRAMES,
): Boolean {
    scrollTo(targetRow, 0)
    return retryEachFrame(maxFrames, awaitFrame) { isRowLaidOut(targetRow) && focusRow() }
}

/** ~170 ms at 60 Hz: far beyond a normal attach, but bounded so a broken row can't spin forever. */
internal const val WRAP_FOCUS_MAX_FRAMES = 10
