package com.seastreams.tv.ui.screens.player

import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.domain.playback.PlaybackProgressRecorder
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.playback.PlayerProgressTracker
import com.seastreams.tv.testutil.FakeWatchProgressDao
import com.seastreams.tv.testutil.testDetails
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * PRD §9: progress is saved only for the stream ExoPlayer actually holds. Mirrors `PlayerViewModel`:
 * `startMedia` → [PlayerProgressBinding.unbind], `handOff` → [PlayerProgressBinding.bind], and every save
 * (tick, pause, Back exit, `STATE_ENDED`) goes through [PlayerProgressBinding.savable].
 */
class PlayerProgressBindingTest {

    private val dao = FakeWatchProgressDao()
    private val tracker = PlayerProgressTracker(PlaybackProgressRecorder(dao, Clock.fixed(Instant.ofEpochMilli(9_000), ZoneOffset.UTC)))
    private val show = testDetails(7, MediaType.TV, seasons = listOf(SeasonInfo(1, "Season 1", 8)), runtimeMinutes = 50)
    private val e01 = media(EpisodeRef(1, 1))
    private val e02 = media(EpisodeRef(1, 2))
    private val minute = 60_000L

    // Same sessionId: an in-player episode switch keeps the session (PlayerSwitching.start).
    private fun media(ref: EpisodeRef) =
        PlaybackRequest.Media(1, PlaybackTarget(1, show, "tt7", ref, episodeRuntimeMinutes = 50), emptyList(), 0, emptyList(), 0)

    /** What every PlayerViewModel save does: only the bound request, with the player's position. */
    private suspend fun PlayerProgressBinding.saveAs(current: PlaybackRequest, positionMs: Long, durationMs: Long) {
        savable(current)?.let { tracker.save(it, positionMs, durationMs) }
    }

    private fun rowOf(ref: EpisodeRef) = dao.rows.value.firstOrNull { it.seasonNumber == ref.season && it.episodeNumber == ref.episode }

    @Test
    fun `the previous episode's 49 of 50 minutes is never saved under the episode starting next`() = runTest {
        val binding = PlayerProgressBinding()
        binding.bind(e01)
        binding.saveAs(e01, 49 * minute, 50 * minute)

        // Auto-next / Next Episode: E02 is the request, its Debrid/Usenet stream still in preflight, E01 still playing.
        binding.unbind()
        binding.saveAs(e02, 49 * minute + 30_000, 50 * minute) // a tick
        binding.saveAs(e02, 49 * minute + 31_000, 50 * minute) // a quick Back before the hand-off

        assertNull(rowOf(EpisodeRef(1, 2)), "E02 has no progress, so it isn't watched")
        assertEquals(49 * minute, rowOf(EpisodeRef(1, 1))?.positionMs)
    }

    @Test
    fun `a quick Back right after the new episode's stream starts doesn't mark it watched`() = runTest {
        val binding = PlayerProgressBinding()
        binding.bind(e01)
        binding.unbind()
        binding.bind(e02) // handOff: the player now reports E02 from 0
        binding.saveAs(e02, 3_000, 50 * minute)

        assertNull(rowOf(EpisodeRef(1, 2)), "a fresh start under 10 s isn't saved at all")

        binding.saveAs(e02, 120_000, 50 * minute)
        val row = rowOf(EpisodeRef(1, 2))
        assertEquals(120_000L, row?.positionMs)
        assertTrue((row?.positionMs ?: 0) < (row?.durationMs ?: 0) * 0.9, "in progress, not watched")
    }

    @Test
    fun `a stream switch or fallback on the same request stays savable`() {
        val binding = PlayerProgressBinding()
        binding.bind(e01)
        // selectStream / fallback re-hand the same request; it is never unbound in between.
        binding.bind(e01)
        assertSame(e01, binding.savable(e01))
        assertNull(binding.savable(e02))
    }

    @Test
    fun `nothing is savable before the first hand-off or after the session ends`() {
        val binding = PlayerProgressBinding()
        assertNull(binding.savable(e01))
        binding.bind(e01)
        binding.unbind()
        assertNull(binding.savable(e01))
        assertNull(binding.savable(null))
    }

    // ---- STATE_ENDED ------------------------------------------------------------------------------

    @Test
    fun `a late Ended from the previous stream is ignored while the next one is in preflight`() {
        // beginSource() bumped the generation before preflight; the old stream's Ended carries the loaded one.
        assertEquals(EndedAction.IGNORE, endedAction(bound = true, eventGeneration = 4, managerGeneration = 5, switchInFlight = false))
    }

    @Test
    fun `an Ended for a request whose stream isn't handed over yet is ignored`() {
        assertEquals(EndedAction.IGNORE, endedAction(bound = false, eventGeneration = 5, managerGeneration = 5, switchInFlight = false))
    }

    @Test
    fun `an Ended during an in-flight switch only saves, it never starts a second switch`() {
        assertEquals(EndedAction.SAVE_ONLY, endedAction(bound = true, eventGeneration = 5, managerGeneration = 5, switchInFlight = true))
    }

    @Test
    fun `the current stream's Ended finishes the episode`() {
        assertEquals(EndedAction.FINISH, endedAction(bound = true, eventGeneration = 5, managerGeneration = 5, switchInFlight = false))
    }
}
