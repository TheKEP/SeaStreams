package com.seastreams.tv.ui.screens.library

import com.seastreams.tv.core.database.entity.LibrarySortOrder
import com.seastreams.tv.domain.usecase.LibraryFilter
import com.seastreams.tv.ui.theme.awaitNextFrame
import com.seastreams.tv.ui.theme.retryEachFrame

/**
 * What the Library grid shows, as far as a dropdown reset is concerned: the filter, the sort order
 * and the genre actually applied ([LibraryView.appliedGenreId]). The re-emission that only fills in
 * genre names leaves it unchanged.
 */
data class LibraryGridShown(val filter: LibraryFilter, val sort: LibrarySortOrder, val genreId: Int?)

/**
 * A Sort or Genre dropdown pick waiting for its grid (PRD §7). The new list reaches the screen
 * frames later: Sort goes through Room and a profile re-emission, Genre through the off-main
 * derivation. So the grid resets only once a state showing exactly the picked target appears, never
 * on a stale earlier frame. Only the latest target waits, and it combines picks made before an earlier
 * one landed. Sidebar filter switches never arm a reset. A state under a different filter drops any
 * pending pick, so moving along the sidebar can never jump focus to the grid. Plain field: no recomposition.
 */
class GridFocusReset {
    private var pending: LibraryGridShown? = null

    /** A Sort pick: `true` (persist it) only when it changes the order; re-picking the active sort is a no-op. */
    fun requestSort(picked: LibrarySortOrder, shown: LibraryGridShown): Boolean {
        val base = pending ?: shown
        if (picked == base.sort) return false
        pending = base.copy(sort = picked)
        return true
    }

    /**
     * A Genre pick: `true` only when it changes the applied genre. Re-picking it, or picking
     * `All Genres` while none is applied (none selected, or one left unapplied under this filter), is a no-op.
     */
    fun requestGenre(picked: Int?, shown: LibraryGridShown): Boolean {
        val base = pending ?: shown
        if (picked == base.genreId) return false
        pending = base.copy(genreId = picked)
        return true
    }

    /** Whether [shown] is the grid the pending pick waits for; answers `true` once. */
    fun awaiting(shown: LibraryGridShown): Boolean {
        val target = pending ?: return false
        if (shown.filter != target.filter) {
            pending = null
            return false
        }
        if (shown != target) return false
        pending = null
        return true
    }
}

/** The grid index whose poster last gained focus, fed by `PosterGrid(onItemFocused)`. Plain field. */
class FocusedPosterMark {
    var index: Int? = null
}

/**
 * Frames the first focus attempt waits after a dropdown pick's grid is shown. `TvDropdown` refocuses
 * its pill on the frame after its close recomposition, and a Genre state can arrive in that same
 * recomposition, so poster 0 is requested strictly after the pill refocus.
 */
const val DROPDOWN_SETTLE_FRAMES = 2

/**
 * A Sort or Genre pick's grid reset (PRD §7): scrolls to the top at once (no animation), then puts
 * focus on the first poster of the new list. With no titles nothing is focused, and focus stays on the
 * dropdown control. Returns whether poster 0 took focus.
 *
 * After [settleFrames] frames, then once per frame (up to [maxFrames] attempts), it does nothing until
 * [firstPosterReady] says index 0 is laid out with the new list's first title. Then it requests
 * [focusFirst]. It succeeds only once [mark] reports index 0 itself focused: a request redirected to
 * another poster, or answered by a stale requester, is retried next frame. A mark left on 0 from
 * before the pick is cleared first, so it never counts.
 */
suspend fun resetGridToFirst(
    hasEntries: Boolean,
    scrollToTop: suspend () -> Unit,
    firstPosterReady: () -> Boolean,
    focusFirst: () -> Boolean,
    mark: FocusedPosterMark,
    maxFrames: Int,
    settleFrames: Int = DROPDOWN_SETTLE_FRAMES,
    awaitFrame: suspend () -> Unit = ::awaitNextFrame,
): Boolean {
    scrollToTop()
    if (!hasEntries) return false
    mark.index = null
    repeat(settleFrames - 1) { awaitFrame() } // retryEachFrame waits the last settle frame itself
    return retryEachFrame(maxFrames, awaitFrame) {
        when {
            !firstPosterReady() -> false
            mark.index == FIRST_POSTER -> true
            else -> focusFirst() && mark.index == FIRST_POSTER
        }
    }
}

private const val FIRST_POSTER = 0
