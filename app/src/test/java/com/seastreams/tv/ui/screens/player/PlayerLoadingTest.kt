package com.seastreams.tv.ui.screens.player

import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.TrailerSource
import com.seastreams.tv.domain.model.TrailerStream
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.testutil.testDetails
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PRD §9 Player Loading & Buffering UI: before a stream's first frame the backdrop + pulsing logo show;
 * after it, buffering shows only the pulsing logo over the video. Trailers have neither.
 */
class PlayerLoadingTest {

    private val episode = PlaybackRequest.Media(
        1, PlaybackTarget(1, testDetails(5, MediaType.TV), "tt5", EpisodeRef(1, 1)), emptyList(), 0, emptyList(), 0,
    )
    private val trailer = PlaybackRequest.Trailer(2, "Trailer", TrailerStream(TrailerSource("https://v"), audio = null, height = 1080))

    @Test
    fun `before the first frame the backdrop shows, buffering or not`() {
        assertEquals(LoadingVisual.BACKDROP_AND_LOGO, loadingVisual(isTrailer = false, started = false, buffering = true))
        assertEquals(LoadingVisual.BACKDROP_AND_LOGO, loadingVisual(isTrailer = false, started = false, buffering = false))
    }

    @Test
    fun `rebuffering after playback began shows only the logo`() {
        assertEquals(LoadingVisual.LOGO_ONLY, loadingVisual(isTrailer = false, started = true, buffering = true))
        assertEquals(LoadingVisual.NONE, loadingVisual(isTrailer = false, started = true, buffering = false))
    }

    @Test
    fun `trailers never show movie or show loading branding`() {
        listOf(true, false).forEach { started ->
            listOf(true, false).forEach { buffering ->
                assertEquals(LoadingVisual.NONE, loadingVisual(isTrailer = true, started = started, buffering = buffering))
            }
        }
        assertEquals(LoadingVisual.NONE, PlayerUiState(request = trailer).loadingVisual)
        assertFalse(PlayerUiState(request = trailer).initialLoading)
    }

    @Test
    fun `a fresh session starts on the initial loading screen`() {
        val state = PlayerUiState(request = episode)
        assertEquals(LoadingVisual.BACKDROP_AND_LOGO, state.loadingVisual)
        assertTrue(state.initialLoading)
    }

    @Test
    fun `stream fallback before the first frame stays on the backdrop`() {
        val first = PlayerUiState(request = episode).onNewStream(0)
        val fallback = first.copy(buffering = true, status = "Stream 1 didn't start. Trying stream 2 of 3…").onNewStream(1)
        assertEquals(1, fallback.streamIndex)
        assertEquals(LoadingVisual.BACKDROP_AND_LOGO, fallback.loadingVisual)
        assertTrue(fallback.initialLoading)
    }

    @Test
    fun `first frame ends initial loading, then a rebuffer shows the logo only`() {
        val playing = PlayerUiState(request = episode).onNewStream(0).onFirstFrame().copy(buffering = false)
        assertEquals(LoadingVisual.NONE, playing.loadingVisual)
        assertFalse(playing.initialLoading)

        val rebuffering = playing.copy(buffering = true)
        assertEquals(LoadingVisual.LOGO_ONLY, rebuffering.loadingVisual)
        assertFalse(rebuffering.initialLoading, "keys work normally while rebuffering")

        assertEquals(LoadingVisual.NONE, rebuffering.copy(buffering = false).loadingVisual)
    }

    @Test
    fun `a stream or episode switch after playback began goes back to the backdrop until its first frame`() {
        val playing = PlayerUiState(request = episode).onNewStream(0).onFirstFrame().copy(buffering = false)
        val switched = playing.onNewStream(2)
        assertEquals(2, switched.streamIndex)
        assertEquals(LoadingVisual.BACKDROP_AND_LOGO, switched.loadingVisual)
        assertEquals(LoadingVisual.NONE, switched.onFirstFrame().loadingVisual)
    }
}
