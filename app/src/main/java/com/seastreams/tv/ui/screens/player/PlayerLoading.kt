package com.seastreams.tv.ui.screens.player

/**
 * What the player draws while a movie or episode isn't showing video (PRD §9 Player Loading &
 * Buffering UI). Trailers keep the plain [BufferingRing] instead.
 */
enum class LoadingVisual {
    NONE,

    /** Initial loading: full-screen backdrop with the pulsing title logo, until the first frame. */
    BACKDROP_AND_LOGO,

    /** Rebuffering after the first frame: only the pulsing logo, over the paused video frame. */
    LOGO_ONLY,
}

/**
 * The initial-loading vs. rebuffering decision. [started] means the current stream has rendered its
 * first frame; until then (stream start, every fallback advance, a stream or episode switch) the
 * backdrop shows whatever ExoPlayer's buffering state says. A [failed] stream (failover off) that had
 * already started isn't rebuffering, so no pulsing logo is drawn over its last frame.
 */
fun loadingVisual(isTrailer: Boolean, started: Boolean, buffering: Boolean, failed: Boolean = false): LoadingVisual = when {
    isTrailer -> LoadingVisual.NONE
    !started -> LoadingVisual.BACKDROP_AND_LOGO
    failed -> LoadingVisual.NONE
    buffering -> LoadingVisual.LOGO_ONLY
    else -> LoadingVisual.NONE
}

/** A new media source was handed to ExoPlayer: it has no frame yet, so initial loading starts again. */
fun PlayerUiState.onNewStream(index: Int): PlayerUiState = copy(streamIndex = index, started = false, streamFailed = false)

/** `onRenderedFirstFrame`: playback has begun; later buffering is rebuffering. */
fun PlayerUiState.onFirstFrame(): PlayerUiState = copy(started = true)
