package com.seastreams.tv.ui.theme

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.launch

/**
 * The outward D-pad keys item [index] of a vertical list ending at [lastIndex] owns under the two-way
 * §1 edge-stop-then-wrap rule: `Up` for the first item, `Down` for the last, both for a single-item
 * list (each press is consumed and focus stays put), none otherwise.
 */
fun verticalEdgeKeys(index: Int, lastIndex: Int): Set<Key> = buildSet {
    if (lastIndex < 0 || index !in 0..lastIndex) return@buildSet
    if (index == 0) add(Key.DirectionUp)
    if (index == lastIndex) add(Key.DirectionDown)
}

/**
 * Two-way edge-stop-then-wrap for item [index] of a vertical list: a fresh `Up` on the first item
 * runs [toLast], a fresh `Down` on the last runs [toFirst], and a held key ([onDpadEdgeWrap]) stops on
 * the edge item. Middle items are untouched.
 */
fun Modifier.verticalListEdgeWrap(index: Int, lastIndex: Int, toFirst: () -> Unit, toLast: () -> Unit): Modifier {
    val keys = verticalEdgeKeys(index, lastIndex)
    return this
        .then(if (Key.DirectionUp in keys) Modifier.onDpadEdgeWrap(Key.DirectionUp, toLast) else Modifier)
        .then(if (Key.DirectionDown in keys) Modifier.onDpadEdgeWrap(Key.DirectionDown, toFirst) else Modifier)
}

/** The two wrap actions of a lazy vertical list: scroll an edge item into view, then focus it. */
class ListEdgeWrapActions(val toFirst: () -> Unit, val toLast: () -> Unit)

/**
 * Wrap actions for a [LazyColumn][androidx.compose.foundation.lazy.LazyColumn] whose first and last
 * items carry [first] and [last]. The latest [lastIndex] is used when a wrap runs, so the list may change.
 */
@Composable
fun rememberListEdgeWrap(
    listState: LazyListState,
    first: FocusRequester,
    last: FocusRequester,
    lastIndex: Int,
): ListEdgeWrapActions {
    val scope = rememberCoroutineScope()
    val currentLastIndex by rememberUpdatedState(lastIndex)
    return remember(listState, first, last) {
        ListEdgeWrapActions(
            toFirst = {
                scope.launch {
                    listState.scrollToItem(0)
                    first.requestFocusAfterFrame()
                }
            },
            toLast = {
                scope.launch {
                    listState.scrollToItem(currentLastIndex.coerceAtLeast(0))
                    last.requestFocusAfterFrame()
                }
            },
        )
    }
}
