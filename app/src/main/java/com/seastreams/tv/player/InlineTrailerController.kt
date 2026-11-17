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

/** What the focused 16:9 card shows. */
sealed interface InlineTrailerState {
    data object Idle : InlineTrailerState

    /** [itemKey]'s trailer is on screen. */
    data class Playing(val itemKey: String, val muted: Boolean) : InlineTrailerState
}

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
    private val trailerKey: suspend (MediaRef) -> String?,
    private val resolveStream: suspend (String) -> TrailerStream,
    private val playback: InlineTrailerPlayback,
) {
    private val _state = MutableStateFlow<InlineTrailerState>(InlineTrailerState.Idle)
    val state: StateFlow<InlineTrailerState> = _state.asStateFlow()

    private var job: Job? = null
    private var currentKey: String? = null
    private val failed = HashSet<String>()

    /** D-pad focus landed on [item]'s card: restart the hover countdown for it. */
    fun onCardFocused(item: MediaItem) {
        cancel()
        val current = settings()
        if (!current.enabled || item.key in failed) return
        currentKey = item.key
        job = scope.launch {
            delay(current.delayMs)
            val key = orNullUnlessCancelled { trailerKey(item.ref) }
            if (key == null) return@launch fail(item.key)
            val stream = orNullUnlessCancelled { resolveStream(key) } ?: return@launch fail(item.key)
            playback.start(
                stream = stream,
                muted = current.muted,
                onReady = { if (currentKey == item.key) _state.value = InlineTrailerState.Playing(item.key, current.muted) },
                onError = { if (currentKey == item.key) fail(item.key) else failed.add(item.key) },
            )
        }
    }

    /** Focus left [itemKey]'s card (or its row); ignored when another card already took over. */
    fun onFocusLeft(itemKey: String) {
        if (currentKey == itemKey) cancel()
    }

    /** Stops any pending or playing preview (held keys, navigation, pause). */
    fun cancel() {
        job?.cancel()
        job = null
        val wasActive = currentKey != null
        currentKey = null
        _state.value = InlineTrailerState.Idle
        if (wasActive) playback.stop()
    }

    private fun fail(itemKey: String) {
        failed.add(itemKey)
        if (currentKey == itemKey) cancel()
    }
}
