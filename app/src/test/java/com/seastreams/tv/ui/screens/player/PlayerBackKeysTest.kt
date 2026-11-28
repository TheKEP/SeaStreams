package com.seastreams.tv.ui.screens.player

import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.domain.model.TrailerSource
import com.seastreams.tv.domain.model.TrailerStream
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.playback.skip.PromptKind
import com.seastreams.tv.testutil.testDetails
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tiered single-press `Back` in the player (PRD §9), media and trailers alike: a sub-overlay closes with focus
 * going back to its bar button, else a Skip / Next episode prompt is dismissed, else the HUD hides, else playback exits.
 */
class PlayerBackKeysTest {

    private val show = testDetails(5, MediaType.TV, seasons = listOf(SeasonInfo(1, "Season 1", 10)))
    private val movie = state(PlaybackRequest.Media(1, PlaybackTarget(1, testDetails(9), "tt9", episode = null), emptyList(), 0, emptyList(), 0))
    private val episode = state(PlaybackRequest.Media(2, PlaybackTarget(1, show, "tt5", EpisodeRef(1, 3)), emptyList(), 0, emptyList(), 0))
    private val trailer = state(PlaybackRequest.Trailer(3, "Trailer", TrailerStream(TrailerSource("https://v"), audio = null, height = 1080)))

    /** Playback has begun (first frame drawn) unless a test says otherwise. */
    private fun state(request: PlaybackRequest) = PlayerUiState(request = request, hudVisible = true, started = true)

    // ---- Back decision: three tiers ----------------------------------------------------------------

    @Test
    fun `tier 1 - Back in every sub-overlay closes only that overlay`() {
        PlayerPanel.entries.forEach { panel ->
            assertEquals(PlayerBackAction.CLOSE_PANEL, playerBackAction(episode.copy(panel = panel)), "$panel")
            assertEquals(PlayerBackAction.CLOSE_PANEL, playerBackAction(movie.copy(panel = panel)), "movie $panel")
        }
    }

    @Test
    fun `tier 2 - Back on the main media HUD hides only the HUD`() {
        assertEquals(PlayerBackAction.HIDE_HUD, playerBackAction(movie))
        assertEquals(PlayerBackAction.HIDE_HUD, playerBackAction(episode))
    }

    @Test
    fun `tier 2 - Back on the full-screen trailer HUD hides only the HUD`() {
        assertEquals(PlayerBackAction.HIDE_HUD, playerBackAction(trailer))
    }

    @Test
    fun `tier 3 - Back with the HUD hidden exits playback, media and trailer`() {
        assertEquals(PlayerBackAction.EXIT, playerBackAction(movie.copy(hudVisible = false)))
        assertEquals(PlayerBackAction.EXIT, playerBackAction(episode.copy(hudVisible = false)))
        assertEquals(PlayerBackAction.EXIT, playerBackAction(trailer.copy(hudVisible = false)))
    }

    // ---- Initial loading screen ---------------------------------------------------------------------

    @Test
    fun `Back on the initial loading screen cancels at once, movie and episode`() {
        assertEquals(PlayerBackAction.CANCEL_LOADING, playerBackAction(movie.copy(hudVisible = false, started = false)))
        assertEquals(PlayerBackAction.CANCEL_LOADING, playerBackAction(episode.copy(hudVisible = false, started = false)))    }

    @Test
    fun `an open overlay or HUD still takes Back first while a switched stream loads`() {
        val loading = episode.copy(started = false)
        assertEquals(PlayerBackAction.CLOSE_PANEL, playerBackAction(loading.copy(panel = PlayerPanel.STREAMS)))
        assertEquals(PlayerBackAction.HIDE_HUD, playerBackAction(loading))
    }

    @Test
    fun `a trailer that hasn't started exits normally - it has no loading screen`() {
        assertEquals(PlayerBackAction.EXIT, playerBackAction(trailer.copy(hudVisible = false, started = false)))
    }

    // ---- Key gate --------------------------------------------------------------------------------

    @Test
    fun `only a fresh Back KeyDown acts - held repeats and the release do nothing`() {
        val gate = PlayerBackKeyGate()
        assertTrue(gate.onBackKey(isKeyDown = true, repeatCount = 0))
        (1..5).forEach { assertFalse(gate.onBackKey(isKeyDown = true, repeatCount = it)) }
        assertFalse(gate.onBackKey(isKeyDown = false, repeatCount = 0), "the trailing KeyUp must not act again")
        assertTrue(gate.onBackKey(isKeyDown = true, repeatCount = 0), "a new press acts again")
    }

    /** Overlay, then HUD, then hidden: one press per tier (trailers have no overlays), held repeats ignored. */
    @Test
    fun `each press steps down exactly one tier`() {
        listOf(episode, trailer).forEach { start ->
            val gate = PlayerBackKeyGate()
            var current = if (start.isTrailer) start else start.copy(panel = PlayerPanel.AUDIO)
            val expected = if (start.isTrailer) {
                listOf(PlayerBackAction.HIDE_HUD, PlayerBackAction.EXIT)
            } else {
                listOf(PlayerBackAction.CLOSE_PANEL, PlayerBackAction.HIDE_HUD, PlayerBackAction.EXIT)
            }
            val actions = mutableListOf<PlayerBackAction>()
            repeat(expected.size) {
                listOf(true to 0, true to 1, true to 2, false to 0).forEach { (down, repeat) ->
                    if (gate.onBackKey(down, repeat)) {
                        val action = playerBackAction(current)
                        actions += action
                        current = when (action) {
                            PlayerBackAction.CLOSE_PANEL -> current.copy(panel = null)
                            PlayerBackAction.DISMISS_PROMPT -> current.copy(prompt = null)
                            PlayerBackAction.HIDE_HUD -> current.copy(hudVisible = false)
                            PlayerBackAction.EXIT, PlayerBackAction.CANCEL_LOADING -> current
                        }
                    }
                }
            }
            assertEquals(expected, actions, if (start.isTrailer) "trailer" else "episode")
        }
    }

    // ---- Focus restore after closing an overlay ----------------------------------------------------

    @Test
    fun `closing Subtitles, Audio, Speed, Streams or Info refocuses that bar button`() {
        listOf(PlayerPanel.SUBTITLES, PlayerPanel.AUDIO, PlayerPanel.SPEED, PlayerPanel.STREAMS, PlayerPanel.INFO).forEach { panel ->
            assertEquals(panel, panelReturnFocus(panel, movie), "movie $panel")
            assertEquals(panel, panelReturnFocus(panel, episode), "episode $panel")
        }
    }

    @Test
    fun `closing Episodes refocuses the Episodes button`() {
        assertEquals(PlayerPanel.EPISODES, panelReturnFocus(PlayerPanel.EPISODES, episode))
    }

    @Test
    fun `a closed overlay whose pill is not shown falls back to Play-Pause`() {
        assertNull(panelReturnFocus(PlayerPanel.EPISODES, movie))
        PlayerPanel.entries.forEach { assertNull(panelReturnFocus(it, trailer), "trailer $it") }
    }

    @Test
    fun `action bar pills - Episodes for episodes only, none for trailers`() {
        assertEquals(PlayerPanel.entries.toList(), PlayerPanel.entries.filter { it.onActionBar(episode) })
        assertEquals(PlayerPanel.entries - PlayerPanel.EPISODES, PlayerPanel.entries.filter { it.onActionBar(movie) })
        assertTrue(PlayerPanel.entries.none { it.onActionBar(trailer) })
    }

    // ---- Skip / Next episode prompts (Milestone 14) ------------------------------------------------

    private val skip = PlayerPromptUi(PromptKind.SKIP_INTRO)
    private val next = PlayerPromptUi(PromptKind.NEXT_EPISODE, "S1:E4", countdownSeconds = 7)

    @Test
    fun `a prompt with the HUD hidden is dismissed by Back instead of leaving playback`() {
        assertEquals(PlayerBackAction.DISMISS_PROMPT, playerBackAction(episode.copy(hudVisible = false, prompt = next)))
        assertEquals(PlayerBackAction.DISMISS_PROMPT, playerBackAction(movie.copy(hudVisible = false, prompt = skip)))
        // Once it is gone, the next Back leaves.
        assertEquals(PlayerBackAction.EXIT, playerBackAction(episode.copy(hudVisible = false, prompt = null)))
    }

    @Test
    fun `a prompt with the HUD visible is dismissed before the HUD hides`() {
        assertEquals(PlayerBackAction.DISMISS_PROMPT, playerBackAction(episode.copy(prompt = skip)))
        assertEquals(PlayerBackAction.HIDE_HUD, playerBackAction(episode.copy(prompt = null)))
    }

    @Test
    fun `Back presses walk prompt, then HUD, then exit`() {
        var current = episode.copy(prompt = next)
        val actions = mutableListOf<PlayerBackAction>()
        repeat(3) {
            val action = playerBackAction(current)
            actions += action
            current = when (action) {
                PlayerBackAction.DISMISS_PROMPT -> current.copy(prompt = null)
                PlayerBackAction.HIDE_HUD -> current.copy(hudVisible = false)
                else -> current
            }
        }
        assertEquals(listOf(PlayerBackAction.DISMISS_PROMPT, PlayerBackAction.HIDE_HUD, PlayerBackAction.EXIT), actions)
    }

    @Test
    fun `an open overlay still closes first`() {
        assertEquals(PlayerBackAction.CLOSE_PANEL, playerBackAction(episode.copy(prompt = skip, panel = PlayerPanel.AUDIO)))
    }

    @Test
    fun `a focused prompt that goes away refocuses the root, or Play-Pause with the HUD up`() {
        assertEquals(PromptFocusRestore.ROOT, promptFocusRestore(promptWasFocused = true, hudVisible = false))
        assertEquals(PromptFocusRestore.PLAY_PAUSE, promptFocusRestore(promptWasFocused = true, hudVisible = true))
        assertNull(promptFocusRestore(promptWasFocused = false, hudVisible = true))
        assertNull(promptFocusRestore(promptWasFocused = false, hudVisible = false))
    }
}
