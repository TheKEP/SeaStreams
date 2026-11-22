package com.seastreams.tv.player

import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.TrailerStream
import com.seastreams.tv.core.util.orNullUnlessCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Auto-Play Trailers settings (PRD §4) as the controller needs them. */
data class InlineTrailerSettings(val enabled: Boolean, val muted: Boolean, val delayMs: Long)

/**
 * One card in one row instance: [rowKey] (unique across tabs and rows), its [index] in the row and
 * the title's [itemKey]. The same title can be wide on several rows at once, so a title alone never
 * identifies which card owns the preview (PRD §4 Single-Card Binding).
 */
data class InlineTrailerSlot(val rowKey: String, val index: Int, val itemKey: String)

/** What the focused 16:9 card shows. */
sealed interface InlineTrailerState {
    data object Idle : InlineTrailerState

    /** The trailer of the card at [slot] is on screen. */
    data class Playing(val slot: InlineTrailerSlot, val muted: Boolean) : InlineTrailerState
}

/**
 * The one rule every card checks before attaching a surface to the shared player: true only for the
 * card that owns the playing [slot] and holds D-pad focus, so at most one surface is ever bound.
 */
fun InlineTrailerState.playsIn(slot: InlineTrailerSlot, cardFocused: Boolean): Boolean =
    cardFocused && this is InlineTrailerState.Playing && this.slot == slot

/** The single shared preview player, as the controller drives it. */
interface InlineTrailerPlayback {
    /** Starts [stream]; exactly one of [onReady] / [onError] is called later on the main thread. */
    suspend fun start(stream: TrailerStream, muted: Boolean, onReady: () -> Unit, onError: () -> Unit)

    fun stop()
}

/**
 * Catalog-row trailer previews (PRD §4 Inline Catalog Row Trailer Auto-Play): after D-pad focus rests
 * on a card for the configured delay, look up its YouTube trailer, extract a stream and play it inside
 * the 16:9 artwork. Every focus move, held-key repeat, screen change or app pause cancels the pending
 * hover job and stops playback at once ([cancel], [onFocusLeft]).
 *
 * Failures are silent: a missing trailer, an extraction error or a playback error simply leaves the
 * static backdrop showing (as if trailers were off) and the title isn't tried again this session.
 * There is deliberately no path to the external YouTube app here.
 */
class InlineTrailerController(
    private val scope: CoroutineScope,
    private val settings: () -> InlineTrailerSettings,
    /** Ranked TMDB trailer candidates; empty when the title has none. */
    private val trailerKeys: suspend (MediaRef) -> List<String>,
    private val resolveStream: suspend (List<String>) -> TrailerStream,
    private val playback: InlineTrailerPlayback,
) {
    private val _state = MutableStateFlow<InlineTrailerState>(InlineTrailerState.Idle)
    val state: StateFlow<InlineTrailerState> = _state.asStateFlow()

    private var job: Job? = null
    private var currentSlot: InlineTrailerSlot? = null

    /** Titles whose trailer failed; skipped on every row for the rest of the session. */
    private val failed = HashSet<String>()

    /** D-pad focus landed on [item]'s card at [slot]: restart the hover countdown for it. */
    fun onCardFocused(slot: InlineTrailerSlot, item: MediaItem) {
        cancel()
        val current = settings()
        if (!current.enabled || item.key in failed) return
        currentSlot = slot
        job = scope.launch {
            delay(current.delayMs)
            val keys = orNullUnlessCancelled { trailerKeys(item.ref) }.orEmpty()
            if (keys.isEmpty()) return@launch fail(slot)
            val stream = orNullUnlessCancelled { resolveStream(keys) } ?: return@launch fail(slot)
            playback.start(
                stream = stream,
                muted = current.muted,
                onReady = { if (currentSlot == slot) _state.value = InlineTrailerState.Playing(slot, current.muted) },
                onError = { fail(slot) },
            )
        }
    }

    /**
     * Focus left the card at [slot] (or its row); ignored when another card already took over, even
     * one showing the same title on another row.
     */
    fun onFocusLeft(slot: InlineTrailerSlot) {
        if (currentSlot == slot) cancel()
    }

    /** Stops any pending or playing preview (held keys, navigation, pause). */
    fun cancel() {
        job?.cancel()
        job = null
        val wasActive = currentSlot != null
        currentSlot = null
        _state.value = InlineTrailerState.Idle
        if (wasActive) playback.stop()
    }

    private fun fail(slot: InlineTrailerSlot) {
        failed.add(slot.itemKey)
        if (currentSlot == slot) cancel()
    }
}
