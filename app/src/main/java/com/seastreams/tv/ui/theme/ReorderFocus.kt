package com.seastreams.tv.ui.theme

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

/** The two reorder buttons of a row. */
enum class MoveButton { UP, DOWN }

/**
 * The button that keeps focus after a row moved by [offset] to [position] of [count]: the one just pressed, or the
 * opposite one once the row reaches the end it was moving towards (`Move up` is useless on the first row).
 */
fun moveButtonAfter(offset: Int, position: Int, count: Int): MoveButton = when {
    count <= 1 -> if (offset < 0) MoveButton.UP else MoveButton.DOWN
    position == 0 -> MoveButton.DOWN
    position == count - 1 -> MoveButton.UP
    offset < 0 -> MoveButton.UP
    else -> MoveButton.DOWN
}

/**
 * Keeps D-pad focus and the viewport on a row being reordered with `Move up` / `Move down` (Manage addons, Usenet
 * providers / indexers, Stream sorting, Preferred filter lists). Each row's buttons take [up] / [down] for its stable
 * key; a click calls [onMove] before the change is saved. When the saved order arrives ([Follow]), the row is scrolled
 * fully into view and focus goes back to its button ([moveButtonAfter]), even if the row's item left composition.
 */
@Stable
class ReorderFocus<K : Any>(val listState: LazyListState) {
    private val upRequesters = HashMap<K, FocusRequester>()
    private val downRequesters = HashMap<K, FocusRequester>()

    internal var pending by mutableStateOf<PendingMove<K>?>(null)
        private set

    fun up(key: K): FocusRequester = upRequesters.getOrPut(key) { FocusRequester() }

    fun down(key: K): FocusRequester = downRequesters.getOrPut(key) { FocusRequester() }

    fun requester(key: K, button: MoveButton): FocusRequester = if (button == MoveButton.UP) up(key) else down(key)

    /** Call on a `Move up` (negative [offset]) / `Move down` click of [key], which sits at [position] of [keys]. */
    fun onMove(key: K, offset: Int, keys: List<K>) {
        val from = keys.indexOf(key)
        if (from < 0) return
        pending = PendingMove(key, offset, (from + offset).coerceIn(0, keys.lastIndex))
    }

    internal fun clear() {
        pending = null
    }
}

/** A move waiting for the saved order: [key] should end up at [expected]. */
internal data class PendingMove<K>(val key: K, val offset: Int, val expected: Int)

@Composable
fun <K : Any> rememberReorderFocus(listState: LazyListState): ReorderFocus<K> = remember(listState) { ReorderFocus(listState) }


/**
 * Follows a pending move once [keys] (the rows' current order) shows it: [followMove] on lazy item [lazyIndexOf] of
 * the row's new position, whose lazy key is [lazyKeyOf]. A row that vanished drops the pending move.
 */
@Composable
fun <K : Any> ReorderFocus<K>.Follow(keys: List<K>, lazyKeyOf: (K) -> Any = { it }, lazyIndexOf: (Int) -> Int = { it }) {
    val move = pending
    LaunchedEffect(keys, move) {
        if (move == null) return@LaunchedEffect
        val position = keys.indexOf(move.key)
        if (position < 0) {
            clear()
            return@LaunchedEffect
        }
        if (position != move.expected) return@LaunchedEffect
        followMove(listState.asRevealList(), move, keys, lazyIndexOf(position), lazyKeyOf(move.key)) { button ->
            requester(move.key, button).tryRequestFocus()
        }
        // Cleared only now: `pending` keys this effect, so clearing first would cancel it mid-flight (and a run
        // cancelled by a newer order leaves the move for the restarted run).
        if (pending == move) clear()
    }
}

/**
 * One followed move, after the saved order reached composition: waits until the reordered list is measured (the
 * layout no longer shows the row at its old index), scrolls lazy item [lazyIndex] fully inside the content area,
 * focuses its move button ([moveButtonAfter]: the pressed one, or the opposite one at the first / last position),
 * then re-checks the row's visibility in case the focus change scrolled the list. Returns whether focus landed.
 */
internal suspend fun <K : Any> followMove(
    list: RevealList,
    move: PendingMove<K>,
    keys: List<K>,
    lazyIndex: Int,
    lazyKey: Any,
    awaitFrame: suspend () -> Unit = ::awaitNextFrame,
    focus: (MoveButton) -> Boolean,
): Boolean {
    var frames = 0
    while (!orderCommitted(list.layout, lazyIndex, lazyKey) && frames++ < COMMIT_FRAMES) awaitFrame()
    list.revealItem(lazyIndex, awaitFrame)
    val button = moveButtonAfter(move.offset, keys.indexOf(move.key), keys.size)
    val focused = retryEachFrame(FOCUS_FRAMES, awaitFrame) { focus(button) }
    list.revealItem(lazyIndex, awaitFrame)
    return focused
}

private const val COMMIT_FRAMES = 10
