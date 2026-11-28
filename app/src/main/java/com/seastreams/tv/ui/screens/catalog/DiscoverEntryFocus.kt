package com.seastreams.tv.ui.screens.catalog

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.seastreams.tv.domain.model.CatalogRowState
import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.ui.components.TabChrome
import com.seastreams.tv.ui.theme.requestFocusAfterFrame

/** Where focus goes when Movies / TV Shows is composed. */
internal enum class EntryFocus {
    /** Top-level tab switch: the tab's own pill in the nav bar, page held at its top. */
    TAB_PILL,

    /** Back from a row's View More: that row's first card. */
    RETURN_ROW,
}

internal fun entryFocusFor(returnRowId: String?): EntryFocus = if (returnRowId == null) EntryFocus.TAB_PILL else EntryFocus.RETURN_ROW

/**
 * A fresh entry into Movies / TV Shows (not a return from View More): focus goes to the tab's pill
 * in the nav bar and the page is held at its top until the user moves focus down into it.
 */
@Stable
internal class TopEntryState(freshEntry: Boolean) {
    /** The page is held at item 0, ignoring the lazy list's key-anchored scroll position. */
    var pinned by mutableStateOf(freshEntry)
        private set

    /** The tab's pill has taken focus (or gave up trying): from now on, page focus is the user's doing. */
    var pillSettled by mutableStateOf(!freshEntry)
        private set

    fun onPillSettled() {
        pillSettled = true
    }

    fun onContentFocused() {
        pinned = stillPinned(pinned, pillSettled, contentFocused = true)
    }

    /** While pinned, puts [listState] back at `(0, 0)`, whatever item the list was anchored to. */
    fun holdTop(listState: LazyListState) {
        if (pinned) listState.requestScrollToItem(index = 0, scrollOffset = 0)
    }
}

/** The page stays pinned to the top until the user (not the initial focus pass) focuses into it. */
internal fun stillPinned(pinned: Boolean, pillSettled: Boolean, contentFocused: Boolean): Boolean =
    pinned && !(pillSettled && contentFocused)

/**
 * The bottom `Streaming services` bar is only composed once the catalog rows exist. The view model's
 * first emission for a profile has no rows; composing the bar then makes it the list's only (so
 * first visible) item, and a keyed lazy list keeps that item anchored in view when the rows are
 * inserted above it a frame later, i.e. the page opens at its bottom. `Popular` is always the first
 * spec and every row is emitted at once (loading), so once [rows] is non-empty, row 0 sits above the bar.
 */
internal fun providerBarReady(rows: List<CatalogRowState>): Boolean = rows.isNotEmpty()

/** How focus entering the page's list is handled. */
internal enum class NavDownEntry { FIRST_CARD, SCROLL_TOP_THEN_FIRST_CARD, STAY_ON_NAV, DEFAULT }

/**
 * `Down` from the nav bar lands on card #1 of the first row ([firstRow]), scrolling back to the top
 * first when that row is off screen. While it is still loading (or before any rows exist) focus
 * stays on the nav bar, so default focus search can't skip ahead to a lower row or the bottom bar.
 * A failed first row has no cards, and a page whose rows all loaded empty shows only the bar
 * ([barShown]): default search takes over. Other entries keep their own target.
 */
internal fun navDownEntry(fromAbove: Boolean, firstRow: RowContent?, firstRowVisible: Boolean, barShown: Boolean): NavDownEntry = when {
    !fromAbove -> NavDownEntry.DEFAULT
    firstRow == null -> if (barShown) NavDownEntry.DEFAULT else NavDownEntry.STAY_ON_NAV
    firstRow is RowContent.Loading -> NavDownEntry.STAY_ON_NAV
    firstRow !is RowContent.Loaded -> NavDownEntry.DEFAULT
    firstRowVisible -> NavDownEntry.FIRST_CARD
    else -> NavDownEntry.SCROLL_TOP_THEN_FIRST_CARD
}

/**
 * The bottom `Streaming services` bar can't take focus until entry focus has settled on the tab's
 * pill, nor while a return-from-View More is still being restored: otherwise it can grab the
 * initial focus and drag the page to the bottom.
 */
internal fun providerBarFocusable(pillSettled: Boolean, returnPending: Boolean): Boolean = pillSettled && !returnPending

/**
 * Runs [state]: focuses the tab's pill once the profile (and so the pill) exists, and while pinned
 * scrolls the list to `(0, 0)` when the initial catalog rows arrive ([rowsReady]), so the page
 * always opens on `Popular`. The page body must also run `SideEffect { state.holdTop(listState) }`
 * itself: it recomposes on every emission, whereas this (skippable) effect is skipped while its
 * arguments stay equal, which would let the list drift to its key anchor in between.
 */
@Composable
internal fun TopEntryEffect(
    state: TopEntryState,
    profileReady: Boolean,
    rowsReady: Boolean,
    chrome: TabChrome,
    listState: LazyListState,
) {
    LaunchedEffect(rowsReady) {
        if (rowsReady && state.pinned) listState.scrollToItem(index = 0, scrollOffset = 0)
    }
    LaunchedEffect(profileReady) {
        if (!profileReady || state.pillSettled) return@LaunchedEffect
        // The pill is composed with the profile; give it a few frames to attach.
        var attempts = 0
        while (attempts < PILL_FOCUS_ATTEMPTS && !chrome.isTabPillFocused && !chrome.tabPill.requestFocusAfterFrame()) attempts++
        state.onPillSettled()
    }
}

/**
 * Whether row [rowIndex] keeps its info panel (first card's title, metadata and synopsis) shown without holding focus:
 * the top `Popular` row does while focus is outside the page's rows (on the nav bar at entry), so the tab opens with
 * that row already expanded and the pill keeps focus. Once a row or the toggles take focus, normal rules apply.
 */
internal fun topRowInfoHeld(rowIndex: Int, contentFocused: Boolean): Boolean = rowIndex == 0 && !contentFocused

@Composable
internal fun rememberTopEntryState(freshEntry: Boolean): TopEntryState = remember { TopEntryState(freshEntry) }

private const val PILL_FOCUS_ATTEMPTS = 5
