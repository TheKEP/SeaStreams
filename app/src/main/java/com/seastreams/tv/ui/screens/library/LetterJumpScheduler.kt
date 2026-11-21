package com.seastreams.tv.ui.screens.library

import androidx.compose.ui.focus.FocusRequester
import com.seastreams.tv.ui.theme.awaitNextFrame
import com.seastreams.tv.ui.theme.retryEachFrame
import com.seastreams.tv.ui.theme.tryRequestFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pause after the last letter focus before the Library grid follows the A–Z bar. */
const val LETTER_PREVIEW_DEBOUNCE_MILLIS = 100L

/**
 * Drives the Library grid from the A–Z quick-jump bar with at most one scroll job in flight.
 *
 * - [preview] (a letter gained focus) waits [debounceMillis] before scrolling, so holding `Up`/`Down`
 *   across letters scrolls the grid once, on the letter the user stops on, instead of composing and
 *   loading posters for every letter passed through.
 * - [jump] (`OK` or `Left` on a letter) scrolls immediately, then runs its follow-up (focus the title).
 *
 * Every call cancels the previous job first, so a pending preview never fires after a newer preview,
 * a jump, or [cancel]. Plain class with no Compose state: scrubbing never recomposes the screen.
 */
class LetterJumpScheduler(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = LETTER_PREVIEW_DEBOUNCE_MILLIS,
    private val scrollTo: suspend (index: Int) -> Unit,
) {
    private var job: Job? = null

    fun preview(index: Int) {
        job?.cancel()
        job = scope.launch {
            delay(debounceMillis)
            scrollTo(index)
        }
    }

    fun jump(index: Int, after: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            scrollTo(index)
            after()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/**
 * One [FocusRequester] per Library grid index, attached to every composed poster. A letter jump
 * focuses only its own target's requester, so a previous jump target that is still composed can
 * never take the focus meant for the new one. Recreate it with the entries list.
 */
class JumpFocusTargets {
    private val requesters = HashMap<Int, FocusRequester>()

    fun requesterFor(index: Int): FocusRequester = requesters.getOrPut(index) { FocusRequester() }
}

/**
 * Focuses exactly poster [index], retrying once per frame (up to [maxFrames]) while it attaches
 * after the jump scroll. An unattached requester fails [tryFocus], so focus stays on the letter.
 */
suspend fun JumpFocusTargets.focus(
    index: Int,
    maxFrames: Int,
    awaitFrame: suspend () -> Unit = ::awaitNextFrame,
    tryFocus: (FocusRequester) -> Boolean = { it.tryRequestFocus() },
): Boolean = retryEachFrame(maxFrames, awaitFrame) { tryFocus(requesterFor(index)) }
