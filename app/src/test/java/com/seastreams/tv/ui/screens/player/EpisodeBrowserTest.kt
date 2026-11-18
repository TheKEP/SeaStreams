package com.seastreams.tv.ui.screens.player

import com.seastreams.tv.domain.model.Episode
import com.seastreams.tv.domain.model.EpisodeRef
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** The in-player Episodes overlay follows the playing episode after a (shuffled) episode change. */
class EpisodeBrowserTest {

    private val loaded = EpisodeBrowser(season = 1, episodes = listOf(mockk<Episode>()), playing = EpisodeRef(1, 2))

    @Test
    fun `an episode in the loaded season keeps the loaded list`() {
        val next = loaded.following(EpisodeRef(1, 3))

        assertEquals(EpisodeRef(1, 3), next.playing)
        assertEquals(1, next.season)
        assertSame(loaded.episodes, next.episodes)
    }

    @Test
    fun `a jump to another season switches the overlay to that season`() {
        val next = loaded.following(EpisodeRef(4, 7))

        assertEquals(EpisodeRef(4, 7), next.playing)
        assertEquals(4, next.season)
        assertEquals(emptyList<Episode>(), next.episodes)
    }
}
