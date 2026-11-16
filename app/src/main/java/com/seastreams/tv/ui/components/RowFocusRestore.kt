package com.seastreams.tv.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import com.seastreams.tv.domain.model.RowContent

/**
 * The catalog row whose `View More` card opened the page now on top, recorded on [tab] before
 * navigating so that `Back` from View More can land on that row's first card instead of the nav bar.
 */
data class RowReturnTarget(val tab: NavTab, val rowId: String)

/** The row id [tab] should restore focus to, or `null` when nothing is pending for that tab. */
fun RowReturnTarget?.rowIdFor(tab: NavTab): String? = this?.takeIf { it.tab == tab }?.rowId

/**
 * Handed to a tab screen: [rowId] is the row to return to (`null` = normal entry focus), and
 * [consume] clears it once focus has landed, so later `Back` presses behave as usual (PRD §1).
 */
@Immutable
class ReturnRowFocus(val rowId: String?, val consume: () -> Unit) {
    companion object {
        val None = ReturnRowFocus(rowId = null, consume = {})
    }
}

/** What a screen does about a pending return-to-row request. */
sealed interface RowRestoreStep {
    /** Scroll the page so the row at [listIndex] is shown; the row then resets to card 0 and focuses it. */
    data class ScrollTo(val listIndex: Int) : RowRestoreStep

    /** The row is still loading: try again once it has loaded. */
    data object Wait : RowRestoreStep

    /** The row is gone or can't take focus: drop the request and use the screen's default focus. */
    data object Abandon : RowRestoreStep
}

/** The card index a row lands on when focus returns to it from View More. */
const val RETURN_CARD_INDEX = 0

/**
 * Decides the restore step for a row found at [listIndex] (`-1` = not on the page) whose content is
 * [content]. Only a loaded row has cards that can take focus.
 */
fun resolveRowRestore(listIndex: Int, content: RowContent?): RowRestoreStep = when {
    listIndex < 0 || content == null -> RowRestoreStep.Abandon
    content is RowContent.Loading -> RowRestoreStep.Wait
    content is RowContent.Loaded -> RowRestoreStep.ScrollTo(listIndex)
    else -> RowRestoreStep.Abandon
}

/**
 * Carries out [step] on the screen's vertical [listState]. [onAbandon] must focus the screen's default
 * target first and only then consume the request (consuming restarts this effect, cancelling it).
 */
@Composable
fun RestoreRowFocusEffect(step: RowRestoreStep?, listState: LazyListState, onAbandon: suspend () -> Unit) {
    LaunchedEffect(step) {
        when (step) {
            is RowRestoreStep.ScrollTo -> listState.scrollToItem(step.listIndex)
            RowRestoreStep.Abandon -> onAbandon()
            RowRestoreStep.Wait, null -> Unit
        }
    }
}
