package com.seastreams.tv.ui.screens.search

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.ui.focus.FocusRequester
import com.seastreams.tv.ui.theme.requestFocusAfterFrame

/** The focusable controls of the Search top row, left to right. */
enum class TopBarControl { MIC, FIELD, CLEAR }

/**
 * Deterministic focus routing between the Search top row and the recent-search chips (PRD §7):
 * - `Down` from the mic, the search field or the clear `X` always lands on the first (leftmost)
 *   chip, with the chip row scrolled back to its start, never on a chip picked by spatial search;
 * - `Up` from any chip returns to the top-row control focus came down from (the mic by default);
 * - `Up` from the top of the content (first category, top grid row) enters the top area through
 *   [enterFromContent]: chip #1 when chips exist, else that same top-row control.
 *
 * The last-focused control is a plain field, so focus moves never recompose the screen.
 */
@Stable
class SearchTopBarFocus(
    private val scrollToStart: suspend (LazyListState) -> Unit = { it.scrollToItem(0) },
    private val requestFocus: suspend (FocusRequester) -> Boolean = { it.requestFocusAfterFrame() },
) {
    val chipsState = LazyListState()
    val firstChip = FocusRequester()
    val field = FocusRequester()
    val clear = FocusRequester()

    private var lastTop = TopBarControl.MIC

    fun onTopFocused(control: TopBarControl) {
        lastTop = control
    }

    /** Scrolls the chip row back to its start, then focuses chip #1. Returns whether focus moved. */
    suspend fun focusFirstChip(): Boolean {
        scrollToStart(chipsState)
        return requestFocus(firstChip)
    }

    /**
     * Where `Up` from the chips goes: the control last focused in the top row (the mic by default).
     * A remembered clear `X` that is no longer shown ([clearShown] `false`) falls back to the field.
     */
    fun upTarget(clearShown: Boolean): TopBarControl =
        if (lastTop == TopBarControl.CLEAR && !clearShown) TopBarControl.FIELD else lastTop

    /** The focus requester of a top-row [control]; the mic's is owned by the screen. */
    fun requesterFor(control: TopBarControl, mic: FocusRequester): FocusRequester = when (control) {
        TopBarControl.MIC -> mic
        TopBarControl.FIELD -> field
        TopBarControl.CLEAR -> clear
    }

    /**
     * `Up` from the top edge of the content: chip #1 (row scrolled to its start) when [hasChips],
     * otherwise the top-row control from [upTarget]. Each failed request falls back one step (chip →
     * top-row control → mic). Returns whether focus moved; if not, it stays where it was.
     */
    suspend fun enterFromContent(hasChips: Boolean, clearShown: Boolean, mic: FocusRequester): Boolean {
        if (hasChips && focusFirstChip()) return true
        val target = upTarget(clearShown)
        if (requestFocus(requesterFor(target, mic))) return true
        return target != TopBarControl.MIC && requestFocus(mic)
    }
}
