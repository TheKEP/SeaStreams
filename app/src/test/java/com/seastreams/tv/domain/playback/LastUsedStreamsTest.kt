package com.seastreams.tv.domain.playback

import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.testutil.FakeLastUsedStreamDao
import com.seastreams.tv.testutil.testDetails
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** PRD §6 `LAST USED`: tiered stream matching and per-profile, per-movie/episode persistence. */
class LastUsedStreamsTest {

    private val dao = FakeLastUsedStreamDao()
    private val recorder = LastUsedStreamRecorder(dao, Clock.fixed(Instant.ofEpochMilli(42), ZoneOffset.UTC))

    private val movie = PlaybackTarget(1L, testDetails(tmdbId = 7), "tt7", episode = null)
    private val show = testDetails(tmdbId = 9, type = MediaType.TV)
    private val s1e1 = PlaybackTarget(1L, show, "tt9", EpisodeRef(1, 1))
    private val s1e2 = PlaybackTarget(1L, show, "tt9", EpisodeRef(1, 2))

    // --- matching ---

    @Test
    fun `an exact URL match wins over an earlier same-label stream`() {
        val streams = listOf(stream("a", url = "u1", desc = "x"), stream("a", url = "u2", desc = "x"))
        assertEquals(1, lastUsedIndex(streams, saved("a", url = "u2", desc = "x")))
    }

    @Test
    fun `a re-signed URL still matches on addon, label and description`() {
        val streams = listOf(stream("a", url = "u1?token=1", desc = "Movie.2160p.mkv"), stream("a", url = "u2?token=1", desc = "Movie.1080p.mkv"))
        assertEquals(1, lastUsedIndex(streams, saved("a", url = "u2?token=OLD", desc = "Movie.1080p.mkv")))
    }

    @Test
    fun `addon and label alone match only when that pair is unique`() {
        val unique = listOf(stream("a", url = "u1", desc = "seeders 10"), stream("b", url = "u2", desc = "x"))
        assertEquals(0, lastUsedIndex(unique, saved("a", url = "gone", desc = "seeders 12")))

        val ambiguous = listOf(stream("a", url = "u1", desc = "one"), stream("a", url = "u2", desc = "two"))
        assertNull(lastUsedIndex(ambiguous, saved("a", url = "gone", desc = "three")))
    }

    @Test
    fun `a stream from another addon never matches`() {
        val streams = listOf(stream("a", url = "u1", desc = "x", addon = "https://other"))
        assertNull(lastUsedIndex(streams, saved("a", url = "u9", desc = "x")))
    }

    @Test
    fun `nothing saved or nothing listed means no match`() {
        assertNull(lastUsedIndex(listOf(stream("a", url = "u1", desc = "")), null))
        assertNull(lastUsedIndex(emptyList(), saved("a", url = "u1", desc = "")))
    }

    // --- persistence ---

    @Test
    fun `a recorded movie stream is found in a re-fetched list`() = runBlocking {
        recorder.record(movie, stream("a", url = "u2", desc = "x"))
        val refetched = listOf(stream("b", url = "u1", desc = "y"), stream("a", url = "u2", desc = "x"))
        assertEquals(1, recorder.indexIn(movie, refetched))
        val row = dao.rows.value.single()
        assertEquals(0, row.seasonNumber)
        assertEquals(0, row.episodeNumber)
        assertEquals(42L, row.updatedAtMillis)
    }

    @Test
    fun `recording again replaces the previous stream`() = runBlocking {
        recorder.record(movie, stream("a", url = "u1", desc = ""))
        recorder.record(movie, stream("b", url = "u2", desc = ""))
        assertEquals(1, dao.rows.value.size)
        assertEquals("u2", recorder.lastUsed(movie)?.url)
    }

    @Test
    fun `each episode keeps its own stream`() = runBlocking {
        recorder.record(s1e1, stream("a", url = "e1", desc = ""))
        assertEquals("e1", recorder.lastUsed(s1e1)?.url)
        assertNull(recorder.lastUsed(s1e2))
    }

    @Test
    fun `another profile never sees this profile's stream`() = runBlocking {
        recorder.record(movie, stream("a", url = "u1", desc = ""))
        val otherProfile = movie.copy(profileId = 2L)
        assertNull(recorder.lastUsed(otherProfile))
        assertNull(recorder.indexIn(otherProfile, listOf(stream("a", url = "u1", desc = ""))))

        recorder.record(otherProfile, stream("b", url = "u2", desc = ""))
        assertEquals("u1", recorder.lastUsed(movie)?.url)
        assertEquals("u2", recorder.lastUsed(otherProfile)?.url)
    }

    private fun stream(label: String, url: String, desc: String, addon: String = "https://addon") =
        StreamSource(addon, "Addon", url, label, desc, emptyMap())

    private fun saved(label: String, url: String, desc: String) = LastUsedStream("https://addon", url, label, desc)
}
