package com.seastreams.tv.ui.screens.library

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
 * - [jump] (`OK` on a letter) scrolls immediately, then runs its follow-up (focus the title).
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
