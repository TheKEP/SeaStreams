package com.seastreams.tv.ui.screens.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.focus.FocusRequester
import com.seastreams.tv.domain.usecase.LibraryFilter
import com.seastreams.tv.domain.usecase.LibraryQuery
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Where Library's horizontal two-way wrap lands (PRD §7, §1 edge-stop-then-wrap): a fresh `Left` on
 * a filter (`All`, `Movies`, `TV Shows`) wraps to the A–Z bar's last focused letter (`#` until one
 * has been focused); a fresh `Right` on the bar wraps back to the filter that last held focus (the
 * selected filter until one has). Plain fields: focus moves never trigger a recomposition.
 */
class LibraryWrapMemory {
    private var lastFilter: LibraryFilter? = null
    private var lastLetter: Int = 0

    fun onFilterFocused(filter: LibraryFilter) {
        lastFilter = filter
    }

    fun onLetterFocused(index: Int) {
        if (index in LibraryQuery.LETTERS.indices) lastLetter = index
    }

    /** The filter a fresh `Right` on the bar focuses; [selected] until a filter has been focused. */
    fun filterTarget(selected: LibraryFilter): LibraryFilter = lastFilter ?: selected

    /** The letter index a fresh `Left` on a filter focuses; `0` (`#`) until a letter has been focused. */
    fun letterTarget(): Int = lastLetter
}

/**
 * The A–Z bar's scroll state and one focus requester per letter, hoisted so the filter sidebar can
 * wrap onto any letter. [wrapTo] scrolls the letter into view, then focuses it (lazy items attach late).
 */
class LetterBarFocus(val listState: LazyListState = LazyListState()) {
    val requesters: List<FocusRequester> = LibraryQuery.LETTERS.map { FocusRequester() }

    fun wrapTo(scope: CoroutineScope, index: Int) {
        val target = index.coerceIn(0, requesters.lastIndex)
        scope.launch {
            listState.scrollToItem(target)
            requesters[target].requestFocusAfterFrame()
        }
    }
}
