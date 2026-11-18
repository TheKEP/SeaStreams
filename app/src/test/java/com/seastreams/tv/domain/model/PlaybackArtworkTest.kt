package com.seastreams.tv.domain.model

import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.testutil.testDetails
import com.seastreams.tv.testutil.testMedia
import com.seastreams.tv.ui.screens.playback.cardArtwork
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The loading screen's backdrop/logo/title (PRD §9 Player Loading & Buffering UI). */
class PlaybackArtworkTest {

    @Test
    fun `prefers the large hero backdrop and keeps the logo`() {
        val media = testMedia(1).copy(backdropUrl = "https://img/w780/b.jpg", heroBackdropUrl = "https://img/w1280/b.jpg", logoUrl = "https://img/logo.png")
        assertEquals(PlaybackArtwork("https://img/w1280/b.jpg", "https://img/logo.png", "Title 1"), media.playbackArtwork())
    }

    @Test
    fun `falls back to the card backdrop when there is no hero size`() {
        val media = testMedia(1).copy(backdropUrl = "https://img/w780/b.jpg", heroBackdropUrl = null)
        assertEquals("https://img/w780/b.jpg", media.playbackArtwork().backdropUrl)
    }

    @Test
    fun `without a logo the title text is the fallback`() {
        val artwork = testMedia(7).playbackArtwork()
        assertNull(artwork.logoUrl)
        assertEquals("Title 7", artwork.title)
    }

    @Test
    fun `a media request's artwork follows its target through an episode switch`() {
        val show = testDetails(5, MediaType.TV)
        val branded = show.copy(media = show.media.copy(heroBackdropUrl = "https://img/hero.jpg", logoUrl = "https://img/logo.png"))
        val request = PlaybackRequest.Media(1, PlaybackTarget(1, branded, "tt5", EpisodeRef(1, 1)), emptyList(), 0, emptyList(), 0)
        val switched = request.copy(target = request.target.copy(episode = EpisodeRef(1, 2)))

        assertEquals(PlaybackArtwork("https://img/hero.jpg", "https://img/logo.png", "Title 5"), request.artwork)
        assertEquals(request.artwork, switched.artwork)
    }

    @Test
    fun `a Continue Watching card without a logo shows the backdrop alone until details load`() {
        val noLogo = ContinueWatchingItem(testMedia(3).copy(heroBackdropUrl = "https://img/hero.jpg"), 0, 0, 0.4f)
        assertEquals(PlaybackArtwork("https://img/hero.jpg", null, ""), cardArtwork(noLogo))

        val withLogo = noLogo.copy(media = noLogo.media.copy(logoUrl = "https://img/logo.png"))
        assertEquals(PlaybackArtwork("https://img/hero.jpg", "https://img/logo.png", "Title 3"), cardArtwork(withLogo))
    }
}
