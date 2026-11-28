package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.dao.ShowActivity
import com.seastreams.tv.core.database.entity.NextUpDismissalEntity
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.NextUpMarker
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.testutil.testDetails
import com.seastreams.tv.testutil.testMedia
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Milestone 13: the pure Next Up rules behind the Continue Watching row. */
class NextUpPolicyTest {

    private val seasons = listOf(SeasonInfo(1, "Season 1", 10), SeasonInfo(2, "Season 2", 8))

    private fun activity(tmdbId: Int, season: Int, episode: Int, updated: Long, finished: Boolean = true, inLibrary: Boolean = false) =
        ShowActivity(tmdbId, season, episode, positionMs = if (finished) 95 else 40, durationMs = 100, updatedAtMillis = updated, inLibrary = inLibrary)

    private fun seed(season: Int, episode: Int, updated: Long = 1_000) = NextUpSeed(1, EpisodeRef(season, episode), updated, inLibrary = false)

    private fun show(lastAired: EpisodeRef?, airDate: LocalDate? = null) =
        testDetails(1, MediaType.TV, seasons).copy(lastAired = lastAired, lastAiredDate = airDate)

    @Test
    fun `only shows whose newest episode is finished are seeds, newest first, a tie going to the later episode`() {
        val seeds = NextUpPolicy.candidates(
            listOf(
                activity(1, 1, 3, updated = 300),
                activity(2, 1, 5, updated = 500, finished = false),
                activity(3, 2, 1, updated = 400),
                activity(3, 2, 4, updated = 400),
            ),
        )

        assertEquals(listOf(3 to EpisodeRef(2, 4), 1 to EpisodeRef(1, 3)), seeds.map { it.tmdbId to it.episode })
    }

    @Test
    fun `the newest shows plus every Library show are checked, never more than the cap`() {
        val recent = (1..30).map { activity(it, 1, 1, updated = 10_000L - it) }
        val oldLibrary = activity(99, 1, 1, updated = 1, inLibrary = true)

        val ids = NextUpPolicy.candidates(recent + oldLibrary).map { it.tmdbId }

        assertEquals((1..NextUpPolicy.RECENT_CANDIDATES).toList() + 99, ids)
        val many = (1..60).map { activity(it, 1, 1, updated = 10_000L - it, inLibrary = true) }
        assertEquals(NextUpPolicy.MAX_CANDIDATES, NextUpPolicy.candidates(many).size)
    }

    @Test
    fun `next episode continues in the season, rolls into the next one, and waits for it to air`() {
        assertEquals(EpisodeRef(1, 4), NextUpPolicy.nextEpisode(seed(1, 3), show(lastAired = EpisodeRef(2, 8))))
        assertEquals(EpisodeRef(2, 1), NextUpPolicy.nextEpisode(seed(1, 10), show(lastAired = EpisodeRef(2, 1))))
        assertNull(NextUpPolicy.nextEpisode(seed(1, 10), show(lastAired = EpisodeRef(1, 10))))
        assertNull(NextUpPolicy.nextEpisode(seed(2, 8), show(lastAired = EpisodeRef(2, 8))))
    }

    @Test
    fun `a newly aired next episode ranks by its air day, anything else by the last watch`() {
        val day = LocalDate.of(2026, 11, 20)
        val dayMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val details = show(lastAired = EpisodeRef(1, 4), airDate = day)

        assertEquals(dayMillis, NextUpPolicy.activityAt(seed(1, 3, updated = 5), EpisodeRef(1, 4), details, ZoneOffset.UTC))
        assertEquals(dayMillis + 9, NextUpPolicy.activityAt(seed(1, 3, updated = dayMillis + 9), EpisodeRef(1, 4), details, ZoneOffset.UTC))
        assertEquals(5L, NextUpPolicy.activityAt(seed(1, 2, updated = 5), EpisodeRef(1, 3), details, ZoneOffset.UTC))
    }

    @Test
    fun `a removed card stays hidden until the show is watched again or a newer episode airs`() {
        val removed = NextUpDismissalEntity(1, 1, 1, 4, lastAiredSeason = 1, lastAiredEpisode = 6, dismissedAtMillis = 1_000)
        val aired = EpisodeRef(1, 6)

        assertTrue(NextUpPolicy.isDismissed(removed, EpisodeRef(1, 4), aired, watchedAtMillis = 900))
        assertFalse(NextUpPolicy.isDismissed(removed, EpisodeRef(1, 4), aired, watchedAtMillis = 1_001), "watched since")
        assertFalse(NextUpPolicy.isDismissed(removed, EpisodeRef(1, 5), aired, watchedAtMillis = 900), "another episode")
        assertFalse(NextUpPolicy.isDismissed(removed, EpisodeRef(1, 4), EpisodeRef(1, 7), watchedAtMillis = 900), "new airing")
        assertFalse(NextUpPolicy.isDismissed(null, EpisodeRef(1, 4), aired, watchedAtMillis = 900))
    }

    @Test
    fun `Remove records the card's episode, latest aired episode and time`() {
        val card = ContinueWatchingItem(testMedia(7, MediaType.TV), 2, 3, 0f, NextUpMarker(EpisodeRef(2, 5)))

        assertEquals(NextUpDismissalEntity(4, 7, 2, 3, 2, 5, 123), NextUpPolicy.dismissalFor(4, card, 123))
    }

    @Test
    fun `the row mixes both kinds by activity, keeps one card per show and caps at 10`() {
        fun card(id: Int, at: Long, nextUp: Boolean = false) = ContinueWatchingItem(
            testMedia(id, MediaType.TV), 1, 1, if (nextUp) 0f else 0.5f, if (nextUp) NextUpMarker(null) else null, at,
        )
        val inProgress = listOf(card(1, 50), card(2, 10))
        val nextUp = listOf(card(3, 30, nextUp = true), card(1, 99, nextUp = true))

        assertEquals(listOf(1, 3, 2), NextUpPolicy.merge(inProgress, nextUp).map { it.media.tmdbId })
        assertFalse(NextUpPolicy.merge(inProgress, nextUp).first().isNextUp)
        val lots = (1..15).map { card(it + 100, it.toLong(), nextUp = true) }
        assertEquals(10, NextUpPolicy.merge(inProgress, lots).size)
    }
}
