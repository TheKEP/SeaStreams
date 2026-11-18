package com.seastreams.tv.ui.screens.playback

import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.playback.ResolvedPlayback
import com.seastreams.tv.domain.usecase.StreamResolution
import com.seastreams.tv.testutil.testDetails
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaybackMessagesTest {

    private val movie = PlaybackTarget(1, testDetails(9), "tt9", episode = null)
    private val episode = PlaybackTarget(1, testDetails(5, MediaType.TV), "tt5", EpisodeRef(2, 4))

    private fun playback(target: PlaybackTarget, failed: List<String> = emptyList()) =
        ResolvedPlayback(target, StreamResolution(emptyList(), emptyList(), failed), topIndex = 0)

    @Test
    fun `a movie with every addon responding`() {
        assertEquals("No streams found", noStreamsMessage(playback(movie)))
    }

    @Test
    fun `an episode names its badge`() {
        assertEquals("No streams found for S2:E4", noStreamsMessage(playback(episode)))
    }

    @Test
    fun `addons that didn't respond are listed`() {
        assertEquals(
            "No streams found for S2:E4 (Torrentio, Comet didn't respond)",
            noStreamsMessage(playback(episode, listOf("Torrentio", "Comet"))),
        )
    }
}
