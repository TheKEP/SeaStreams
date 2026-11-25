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

/** What gets focus once a followed row reached its new position. */
sealed interface FollowTarget {
    /** A `Move up` / `Move down` click by [offset] among the [movableCount] reorderable rows: one of its buttons. */
    data class Button(val offset: Int, val movableCount: Int) : FollowTarget

    /** A change that re-sorted the row itself (a value chosen into or out of a Preferred list): the row. */
    data object Row : FollowTarget
}

/**
 * Keeps D-pad focus and the viewport on a row that changes position: reordered with `Move up` / `Move down` (Manage
 * addons, Usenet providers / indexers, Stream sorting, Preferred filter lists), or re-sorted by its own toggle (a
 * value chosen into a Preferred list jumps up to the end of the chosen block; a sort criterion added or removed).
 * Each row's buttons take [up] / [down], and its main control [row], for its stable key. A click calls [onMove] or
 * [onReorder] before the change is saved. When the saved order arrives ([Follow]), the row is scrolled fully into
 * view and focus lands on its button ([moveButtonAfter]) or row, even if the row's item left composition.
 */
@Stable
class ReorderFocus<K : Any>(val listState: LazyListState) {
    private val upRequesters = HashMap<K, FocusRequester>()
    private val downRequesters = HashMap<K, FocusRequester>()
    private val rowRequesters = HashMap<K, FocusRequester>()

    internal var pending by mutableStateOf<PendingMove<K>?>(null)
        private set

    fun up(key: K): FocusRequester = upRequesters.getOrPut(key) { FocusRequester() }

    fun down(key: K): FocusRequester = downRequesters.getOrPut(key) { FocusRequester() }

    fun row(key: K): FocusRequester = rowRequesters.getOrPut(key) { FocusRequester() }

    /** The requester [followMove] focuses: a move button, or the row itself for `null`. */
    fun requester(key: K, button: MoveButton?): FocusRequester = when (button) {
        MoveButton.UP -> up(key)
        MoveButton.DOWN -> down(key)
        null -> row(key)
    }

    /**
     * Call on a `Move up` (negative [offset]) / `Move down` click of [key], one of the reorderable [keys]. They must
     * lead the list given to [Follow], so a position among them is also a position in the whole list.
     */
    fun onMove(key: K, offset: Int, keys: List<K>) {
        val from = keys.indexOf(key)
        if (from < 0) return
        pending = PendingMove(key, (from + offset).coerceIn(0, keys.lastIndex), FollowTarget.Button(offset, keys.size))
    }

    /** Call before saving a change that re-sorts [key]'s row to [expected] in the list given to [Follow]. */
    fun onReorder(key: K, expected: Int) {
        if (expected < 0) return
        pending = PendingMove(key, expected, FollowTarget.Row)
    }

    internal fun clear() {
        pending = null
    }
}

/** A change waiting for the saved order: [key] should end up at [expected], then [target] gets focus. */
internal data class PendingMove<K>(val key: K, val expected: Int, val target: FollowTarget)

@Composable
fun <K : Any> rememberReorderFocus(listState: LazyListState): ReorderFocus<K> = remember(listState) { ReorderFocus(listState) }


/**
 * Follows a pending change once [keys] (every row's current order) shows it: [followMove] on lazy item [lazyIndexOf]
 * of the row's new position, whose lazy key is [lazyKeyOf]. A row that vanished drops the pending change.
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
        followMove(listState.asRevealList(), move, lazyIndexOf(position), lazyKeyOf(move.key)) { button ->
            requester(move.key, button).tryRequestFocus()
        }
        // Cleared only now: `pending` keys this effect, so clearing first would cancel it mid-flight (and a run
        // cancelled by a newer order leaves the move for the restarted run).
        if (pending == move) clear()
    }
}

/**
 * One followed change, after the saved order reached composition: waits until the reordered list is measured (the
 * layout no longer shows the row at its old index), scrolls lazy item [lazyIndex] fully inside the content area,
 * focuses the target ([focusTarget]: a move button, or `null` for the row itself), then re-checks the row's
 * visibility in case the focus change scrolled the list. Returns whether focus landed.
 */
internal suspend fun <K : Any> followMove(
    list: RevealList,
    move: PendingMove<K>,
    lazyIndex: Int,
    lazyKey: Any,
    awaitFrame: suspend () -> Unit = ::awaitNextFrame,
    focus: (MoveButton?) -> Boolean,
): Boolean {
    var frames = 0
    while (!orderCommitted(list.layout, lazyIndex, lazyKey) && frames++ < COMMIT_FRAMES) awaitFrame()
    list.revealItem(lazyIndex, awaitFrame)
    val button = focusTarget(move)
    val focused = retryEachFrame(FOCUS_FRAMES, awaitFrame) { focus(button) }
    list.revealItem(lazyIndex, awaitFrame)
    return focused
}

/**
 * The control focused after [move]: for a button click the pressed button, or the opposite one once the row reached
 * the first / last reorderable position ([moveButtonAfter]); for a re-sorted row, `null` (the row itself).
 */
internal fun focusTarget(move: PendingMove<*>): MoveButton? = when (val target = move.target) {
    is FollowTarget.Button -> moveButtonAfter(target.offset, move.expected, target.movableCount)
    FollowTarget.Row -> null
}

private const val COMMIT_FRAMES = 10
