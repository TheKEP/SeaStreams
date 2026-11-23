package com.seastreams.tv.domain.playback

import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.usecase.StreamResolution
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
    fun `a re-signed URL whose seeder count changed still matches when the rest of the description agrees`() {
        val streams = listOf(
            stream("Torrentio · 4k", url = "r1?sig=new", desc = "Movie.2160p.HDR\n👤 87 💾 21.3 GB ⚙️ TPB"),
            stream("Torrentio · 4k", url = "r2?sig=new", desc = "Movie.2160p.DV\n👤 3 💾 18.0 GB ⚙️ RARBG"),
            stream("Torrentio · 1080p", url = "r3?sig=new", desc = "Movie.1080p\n👤 900 💾 2.1 GB ⚙️ YTS"),
        )
        val saved = saved("Torrentio · 4k", url = "r2?sig=old", desc = "Movie.2160p.DV\n👤 5 💾 18.0 GB ⚙️ RARBG")
        assertEquals(1, lastUsedIndex(streams, saved))
    }

    @Test
    fun `two streams that differ only in numbers are never guessed between`() {
        val streams = listOf(stream("a", url = "u1", desc = "Pack S01\n👤 10"), stream("a", url = "u2", desc = "Pack S01\n👤 20"))
        assertNull(lastUsedIndex(streams, saved("a", url = "gone", desc = "Pack S01\n👤 30")))
    }

    @Test
    fun `the release name line matches only when one stream has it`() {
        val unique = listOf(
            stream("a", url = "u1", desc = "Movie.2160p.mkv\ncached ✔"),
            stream("a", url = "u2", desc = "Movie.1080p.mkv\ncached ✔"),
        )
        assertEquals(1, lastUsedIndex(unique, saved("a", url = "gone", desc = "Movie.1080p.mkv\nnot cached")))

        val ambiguous = listOf(stream("a", url = "u1", desc = "Movie.mkv\nx"), stream("a", url = "u2", desc = "Movie.mkv\ny"))
        assertNull(lastUsedIndex(ambiguous, saved("a", url = "gone", desc = "Movie.mkv\nz")))
    }

    @Test
    fun `an identical description beats a looser match listed earlier`() {
        val streams = listOf(stream("a", url = "u1", desc = "Movie 👤 9"), stream("a", url = "u2", desc = "Movie 👤 4"))
        assertEquals(1, lastUsedIndex(streams, saved("a", url = "gone", desc = "Movie 👤 4")))
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

    @Test
    fun `a row from before fingerprints (blank) never matches on URL`() {
        val streams = listOf(stream("a", url = "", desc = "x"), stream("b", url = "u2", desc = "y"))
        val legacy = LastUsedStream("https://addon", "", "b", "y")
        assertEquals(1, lastUsedIndex(streams, legacy))
    }

    // --- persistence ---

    @Test
    fun `the stored row keeps only a fingerprint of a token-bearing URL`() = runBlocking {
        val url = "https://addon.example/resolve/realdebrid/SECRET-API-KEY/tt7/file.mkv?token=abc"
        recorder.record(movie, stream("a", url = url, desc = "x"))
        val stored = dao.rows.value.single().streamUrlFingerprint
        assertEquals(64, stored.length)
        assertEquals(false, stored.contains("SECRET") || stored.contains("token"))
        assertEquals(0, recorder.indexIn(movie, listOf(stream("a", url = url, desc = "changed"))))
    }

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
        assertEquals(streamUrlFingerprint("u2"), recorder.lastUsed(movie)?.urlFingerprint)
    }

    @Test
    fun `each episode keeps its own stream`() = runBlocking {
        recorder.record(s1e1, stream("a", url = "e1", desc = ""))
        assertEquals(streamUrlFingerprint("e1"), recorder.lastUsed(s1e1)?.urlFingerprint)
        assertNull(recorder.lastUsed(s1e2))
    }

    @Test
    fun `another profile never sees this profile's stream`() = runBlocking {
        recorder.record(movie, stream("a", url = "u1", desc = ""))
        val otherProfile = movie.copy(profileId = 2L)
        assertNull(recorder.lastUsed(otherProfile))
        assertNull(recorder.indexIn(otherProfile, listOf(stream("a", url = "u1", desc = ""))))

        recorder.record(otherProfile, stream("b", url = "u2", desc = ""))
        assertEquals(streamUrlFingerprint("u1"), recorder.lastUsed(movie)?.urlFingerprint)
        assertEquals(streamUrlFingerprint("u2"), recorder.lastUsed(otherProfile)?.urlFingerprint)
    }

    @Test
    fun `a movie and an episode of the same TMDB id keep separate streams`() = runBlocking {
        val showAsMovieId = PlaybackTarget(1L, testDetails(tmdbId = 9), "tt9", episode = null)
        val s2e4 = PlaybackTarget(1L, show, "tt9", EpisodeRef(2, 4))
        recorder.record(showAsMovieId, stream("a", url = "movie", desc = ""))
        recorder.record(s2e4, stream("a", url = "episode", desc = ""))
        assertEquals(streamUrlFingerprint("movie"), recorder.lastUsed(showAsMovieId)?.urlFingerprint)
        assertEquals(streamUrlFingerprint("episode"), recorder.lastUsed(s2e4)?.urlFingerprint)
        assertEquals(2, dao.rows.value.single { it.mediaType == MediaType.TV }.seasonNumber)
    }

    // --- auto-play ---

    @Test
    fun `auto-play starts the LAST USED stream when it is listed`() = runBlocking {
        val streams = listOf(stream("a", url = "u1", desc = ""), stream("b", url = "u2", desc = ""), stream("c", url = "u3", desc = ""))
        recorder.record(s1e2, streams[2])
        assertEquals(2, recorder.autoPlayIndex(resolved(s1e2, streams, topIndex = 0)))
    }

    @Test
    fun `auto-play falls back to the top stream without a listed LAST USED`() = runBlocking {
        val streams = listOf(stream("a", url = "u1", desc = ""), stream("b", url = "u2", desc = ""))
        assertEquals(1, recorder.autoPlayIndex(resolved(movie, streams, topIndex = 1)))

        recorder.record(movie, stream("z", url = "gone", desc = "", addon = "https://other"))
        assertEquals(1, recorder.autoPlayIndex(resolved(movie, streams, topIndex = 1)))
    }

    private fun resolved(target: PlaybackTarget, streams: List<StreamSource>, topIndex: Int) =
        ResolvedPlayback(target, StreamResolution(streams, emptyList(), emptyList()), topIndex)

    private fun stream(label: String, url: String, desc: String, addon: String = "https://addon") =
        StreamSource(addon, "Addon", url, label, desc, emptyMap())

    private fun saved(label: String, url: String, desc: String) = LastUsedStream("https://addon", streamUrlFingerprint(url), label, desc)
}
