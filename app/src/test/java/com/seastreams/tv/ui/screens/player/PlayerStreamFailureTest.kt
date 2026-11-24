package com.seastreams.tv.ui.screens.player

import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.testutil.testDetails
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** PRD §9: a stream that fails with Automatic Stream Failover off stops on a clear failure state. */
class PlayerStreamFailureTest {

    private val episode = PlaybackRequest.Media(
        1, PlaybackTarget(1, testDetails(5, MediaType.TV), "tt5", EpisodeRef(1, 1)), emptyList(), 0, emptyList(), 0,
    )

    @Test
    fun `the status names the failed stream and says how to go on`() {
        assertEquals(
            "Stream 2 of 4 couldn't play. Automatic failover is off — pick another stream.",
            streamFailedMessage(index = 1, total = 4),
        )
        assertEquals(
            "This stream couldn't play. Automatic failover is off — pick another stream.",
            streamFailedMessage(index = 0, total = 1),
        )
        assertEquals(
            "Stream 3 of 12 couldn't play (password-protected archive). Automatic failover is off — pick another stream.",
            streamFailedMessage(index = 2, total = 12, reason = "password-protected archive"),
        )
    }

    @Test
    fun `picking any stream clears the failure`() {
        val failed = PlayerUiState(request = episode, streamIndex = 1, started = true, streamFailed = true)
        val next = failed.onNewStream(2)

        assertFalse(next.streamFailed)
        assertEquals(2, next.streamIndex)
        assertFalse(failed.onNewStream(1).streamFailed, "retrying the same stream clears it too")
    }

    @Test
    fun `a failed stream that had started shows no rebuffering logo over its last frame`() {
        val failed = PlayerUiState(request = episode, started = true, buffering = true, streamFailed = true)
        assertEquals(LoadingVisual.NONE, failed.loadingVisual)
        assertEquals(LoadingVisual.LOGO_ONLY, failed.copy(streamFailed = false).loadingVisual)
    }

    @Test
    fun `a stream that failed before its first frame keeps the backdrop behind the Streams overlay`() {
        val failed = PlayerUiState(request = episode, started = false, streamFailed = true)
        assertEquals(LoadingVisual.BACKDROP_AND_LOGO, failed.loadingVisual)
        // Back still steps: overlay, then HUD, then cancel loading.
        assertEquals(PlayerBackAction.CLOSE_PANEL, playerBackAction(failed.copy(panel = PlayerPanel.STREAMS, hudVisible = true)))
        assertEquals(PlayerBackAction.HIDE_HUD, playerBackAction(failed.copy(hudVisible = true)))
        assertEquals(PlayerBackAction.CANCEL_LOADING, playerBackAction(failed))
    }
}
