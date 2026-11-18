package com.seastreams.tv.domain.usecase

import com.seastreams.tv.data.details.RestrictedTitleException
import com.seastreams.tv.data.details.TitleDetailsRepository
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.Episode
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.testutil.testDetails
import com.seastreams.tv.testutil.testMedia
import com.seastreams.tv.testutil.testProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

/** PRD §3: a Continue Watching card resolves to its own movie/episode, age-checked, without Details. */
class ResolveContinueWatchingTargetUseCaseTest {

    private val profiles = mockk<ProfileRepository>()
    private val titles = mockk<TitleDetailsRepository>()
    private val useCase = ResolveContinueWatchingTargetUseCase(profiles, titles)

    @Test
    fun `a movie card resolves to the movie with its IMDb id`() = runBlocking {
        val details = testDetails(tmdbId = 7)
        coEvery { profiles.getProfile(ADULT) } returns testProfile(ADULT, isKids = false)
        coEvery { titles.load(details.media.ref, null) } returns details
        coEvery { titles.imdbIdForDetails(details.media.ref) } returns "tt0000007"

        val result = useCase(ADULT, ContinueWatchingItem(details.media, 0, 0, 0.4f))

        assertEquals(ContinueWatchingTarget.Ready(PlaybackTarget(ADULT, details, "tt0000007", episode = null)), result)
    }

    @Test
    fun `an episode card plays that exact episode, with its title and runtime`() = runBlocking {
        val details = testDetails(tmdbId = 9, type = MediaType.TV)
        val ref = EpisodeRef(2, 4)
        coEvery { profiles.getProfile(ADULT) } returns testProfile(ADULT, isKids = false)
        coEvery { titles.load(details.media.ref, null) } returns details
        coEvery { titles.imdbIdForDetails(details.media.ref) } returns "tt0000009"
        coEvery { titles.episodes(9, 2) } returns listOf(episode(EpisodeRef(2, 3)), episode(ref, "The One", 42))

        val result = useCase(ADULT, ContinueWatchingItem(details.media, 2, 4, 0.5f))

        assertEquals(
            ContinueWatchingTarget.Ready(PlaybackTarget(ADULT, details, "tt0000009", ref, "The One", 42)),
            result,
        )
    }

    @Test
    fun `an episode still plays when its season can't be loaded`() = runBlocking {
        val details = testDetails(tmdbId = 9, type = MediaType.TV)
        coEvery { profiles.getProfile(ADULT) } returns testProfile(ADULT, isKids = false)
        coEvery { titles.load(details.media.ref, null) } returns details
        coEvery { titles.imdbIdForDetails(details.media.ref) } throws IOException("offline")
        coEvery { titles.episodes(9, 1) } throws IOException("offline")

        val result = useCase(ADULT, ContinueWatchingItem(details.media, 1, 1, 0.5f))

        assertEquals(ContinueWatchingTarget.Ready(PlaybackTarget(ADULT, details, null, EpisodeRef(1, 1))), result)
    }

    @Test
    fun `a Kids profile is checked against its ceiling and a blocked title never resolves`() = runBlocking {
        val kid = testProfile(KIDS, isKids = true)
        val media = testMedia(5)
        coEvery { profiles.getProfile(KIDS) } returns kid
        coEvery { titles.load(media.ref, kid.toAgeRestriction()) } throws RestrictedTitleException()

        assertEquals(ContinueWatchingTarget.Restricted, useCase(KIDS, ContinueWatchingItem(media, 0, 0, 0.3f)))
        coVerify(exactly = 0) { titles.imdbIdForDetails(any()) }
    }

    @Test
    fun `a failed load or a missing profile is unavailable`() = runBlocking {
        val media = testMedia(5)
        coEvery { profiles.getProfile(ADULT) } returns testProfile(ADULT, isKids = false)
        coEvery { profiles.getProfile(MISSING) } returns null
        coEvery { titles.load(media.ref, null) } throws IOException("offline")

        assertEquals(ContinueWatchingTarget.Unavailable, useCase(ADULT, ContinueWatchingItem(media, 0, 0, 0.3f)))
        assertEquals(ContinueWatchingTarget.Unavailable, useCase(MISSING, ContinueWatchingItem(media, 0, 0, 0.3f)))
    }

    private fun episode(ref: EpisodeRef, title: String = "Other", runtime: Int? = 30) =
        Episode(ref, title, overview = "", airDate = null, runtimeMinutes = runtime, stillUrl = null)

    private companion object {
        const val ADULT = 1L
        const val KIDS = 2L
        const val MISSING = 3L
    }
}
