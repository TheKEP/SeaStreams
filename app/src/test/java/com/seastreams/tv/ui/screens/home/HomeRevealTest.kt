package com.seastreams.tv.ui.screens.home

import coil3.size.Scale
import com.seastreams.tv.domain.model.CatalogRow
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.CatalogRowState
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.testutil.testProfile
import com.seastreams.tv.ui.components.HeroImageSize
import com.seastreams.tv.ui.components.HeroLogoSize
import com.seastreams.tv.ui.components.PosterSize
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeRevealTest {

    private val profile = testProfile(1, isKids = false)

    private fun item(id: Int, poster: Boolean = true, logo: Boolean = false) = MediaItem(
        tmdbId = id, mediaType = MediaType.MOVIE, title = "T$id", overview = "",
        posterUrl = if (poster) "p$id" else null, backdropUrl = "b$id", heroBackdropUrl = "h$id",
        logoUrl = if (logo) "l$id" else null, year = null, ratingPercent = null, genres = emptyList(),
        runtimeMinutes = null, certification = null, isAdultContent = false,
    )

    private fun row(content: RowContent) = CatalogRowState(CatalogRowSpec("r", "R", "https://mdblist.com/lists/a/r"), content)

    private val readyState = HomeUiState(
        profile = profile,
        hero = HeroState.Loaded(listOf(item(100, logo = true), item(101))),
        rows = listOf(row(RowContent.Loaded(CatalogRow((1..20).map { item(it) }, hasMore = false))), row(RowContent.Loading)),
    )

    @Test
    fun `first screen is ready once hero and first row have settled`() {
        assertTrue(readyState.isFirstScreenReady())
        assertTrue(readyState.copy(hero = HeroState.Failed).isFirstScreenReady())
        assertTrue(readyState.copy(rows = listOf(row(RowContent.Failed))).isFirstScreenReady())
        assertFalse(readyState.copy(hero = HeroState.Loading).isFirstScreenReady())
        assertFalse(readyState.copy(rows = listOf(row(RowContent.Loading))).isFirstScreenReady())
        assertFalse(readyState.copy(rows = emptyList()).isFirstScreenReady())
    }

    @Test
    fun `first screen images are the hero art then twelve posters, continue watching first`() {
        val resume = ContinueWatchingItem(item(50, poster = false), seasonNumber = 0, episodeNumber = 0, progress = 0.3f)

        val targets = readyState.copy(continueWatching = listOf(resume)).firstScreenImages()

        assertEquals(ImageTarget("h100", HeroImageSize, Scale.FILL), targets[0])
        assertEquals(ImageTarget("l100", HeroLogoSize, Scale.FIT), targets[1])
        val posters = targets.drop(2)
        assertEquals(FIRST_SCREEN_POSTERS, posters.size)
        assertEquals(listOf("b50") + (1..11).map { "p$it" }, posters.map { it.url })
        assertTrue(posters.all { it.size == PosterSize && it.scale == Scale.FILL })
    }

    @Test
    fun `reveal waits for content and images`() = runTest {
        val states = MutableStateFlow(HomeUiState(profile = profile))
        var warmed: List<ImageTarget>? = null

        val reveal = backgroundScope.async { awaitHomeReveal(states, profile.id, { warmed = it; delay(200) }) }
        delay(300)
        states.value = readyState

        assertTrue(reveal.await())
        assertEquals(500, currentTime)
        assertEquals(readyState.firstScreenImages(), warmed)
    }

    @Test
    fun `reveal gives up at the timeout when the API is slow`() = runTest {
        val states = MutableStateFlow(HomeUiState(profile = profile))

        val ready = awaitHomeReveal(states, profile.id, { })

        assertFalse(ready)
        assertEquals(REVEAL_TIMEOUT_MILLIS, currentTime)
    }

    @Test
    fun `slow image decoding is capped by the same timeout`() = runTest {
        val ready = awaitHomeReveal(MutableStateFlow(readyState), profile.id, { delay(5_000) })

        assertFalse(ready)
        assertEquals(REVEAL_TIMEOUT_MILLIS, currentTime)
    }

    @Test
    fun `content cached for another profile does not open the gate`() = runTest {
        val ready = awaitHomeReveal(MutableStateFlow(readyState), profileId = 2, warm = { })

        assertFalse(ready)
    }

    @Test
    fun `a deleted profile opens the gate immediately without warming`() = runTest {
        var warmed = false

        val ready = awaitHomeReveal(MutableStateFlow(HomeUiState(missingProfileId = 1)), 1, { warmed = true })

        assertTrue(ready)
        assertFalse(warmed)
        assertEquals(0, currentTime)
    }
}
