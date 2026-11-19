package com.seastreams.tv.player

import com.seastreams.tv.core.datastore.PlaybackBufferProfile
import com.seastreams.tv.core.datastore.SubtitleMode
import com.seastreams.tv.domain.playback.AudioOption
import com.seastreams.tv.domain.playback.SubtitleOption
import com.seastreams.tv.domain.playback.SubtitleSource
import com.seastreams.tv.domain.playback.TrackOrdering
import com.seastreams.tv.player.subtitle.ExternalSubtitleLoader
import com.seastreams.tv.player.subtitle.ExternalSubtitleTimeline
import com.seastreams.tv.player.subtitle.SubtitleOffset
import com.seastreams.tv.player.subtitle.TimedCue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class PlayerSupportTest {

    // ---- Buffer profiles ----------------------------------------------------------------------------

    @Test
    fun `buffer profiles match PRD sizes and rebuffer thresholds`() {
        val mb = 1024 * 1024
        assertEquals(LoadControlSpec(50_000, 50_000, 2_500, 5_000, 64 * mb), PlaybackBufferProfile.STANDARD.loadControlSpec())
        assertEquals(LoadControlSpec(150_000, 150_000, 2_500, 5_000, 150 * mb), PlaybackBufferProfile.LARGE.loadControlSpec())
        assertEquals(LoadControlSpec(300_000, 300_000, 2_500, 8_000, 250 * mb), PlaybackBufferProfile.MAX.loadControlSpec())
    }

    // ---- Throughput ---------------------------------------------------------------------------------

    @Test
    fun `throughput is a rolling 2 second average`() {
        var now = 10_000L
        val meter = ThroughputMeter(nowMs = { now })
        repeat(20) {
            meter.record(125_000) // 1 Mbit every 100 ms = 10 Mbps
            now += 100
        }
        assertEquals(10.0, meter.megabitsPerSecond(), 0.6)

        now += 2_500
        assertEquals(0.0, meter.megabitsPerSecond(), 0.001, "old samples age out of the window")
    }

    // ---- External subtitle timeline & sync offset ---------------------------------------------------

    private val timeline = ExternalSubtitleTimeline(
        listOf(
            TimedCue(1_000_000, 3_000_000, "One"),
            TimedCue(2_500_000, 4_000_000, "Overlap"),
            TimedCue(10_000_000, 12_000_000, "Later"),
        ),
    )

    @Test
    fun `cues are found by position, including overlaps`() {
        assertEquals(emptyList<String>(), timeline.textsAt(500_000))
        assertEquals(listOf("One"), timeline.textsAt(1_500_000))
        assertEquals(listOf("One", "Overlap"), timeline.textsAt(2_700_000))
        assertEquals(listOf("Overlap"), timeline.textsAt(3_000_000), "end is exclusive")
        assertEquals(listOf("Later"), timeline.textsAt(11_000_000))
        assertEquals(emptyList<String>(), timeline.textsAt(20_000_000))
    }

    @Test
    fun `sync offset moves in 0_1s steps within 60s either way`() {
        assertEquals(100L, SubtitleOffset.step(0, +1))
        assertEquals(-100L, SubtitleOffset.step(0, -1))
        assertEquals(60_000L, SubtitleOffset.step(60_000, +1))
        assertEquals(-60_000L, SubtitleOffset.step(-60_000, -1))
        assertEquals("0.0s", SubtitleOffset.label(0))
        assertEquals("+1.5s", SubtitleOffset.label(1_500))
        assertEquals("-0.3s", SubtitleOffset.label(-300))
        assertEquals("+60.0s", SubtitleOffset.label(60_000))
    }

    @Test
    fun `subtitle files are sniffed and unzipped`() {
        assertEquals("text/vtt", ExternalSubtitleLoader.detectMimeType("﻿WEBVTT\n\n00:01.000 --> 00:02.000\nHi".toByteArray()))
        assertEquals("text/x-ssa", ExternalSubtitleLoader.detectMimeType("[Script Info]\nTitle: x".toByteArray()))
        assertEquals("application/x-subrip", ExternalSubtitleLoader.detectMimeType("1\n00:00:01,000 --> 00:00:02,000\nHi".toByteArray()))

        val plain = "1\n00:00:01,000 --> 00:00:02,000\nHi\n".toByteArray()
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(plain) } }.toByteArray()
        assertArrayEquals(plain, ExternalSubtitleLoader.unzipIfNeeded(zipped))
        assertArrayEquals(plain, ExternalSubtitleLoader.unzipIfNeeded(plain))
    }

    // ---- Track ordering ------------------------------------------------------------------------------

    private val subs = listOf(
        SubtitleOption("0:0", "French", "fr", SubtitleSource.EMBEDDED),
        SubtitleOption("0:1", "English", "eng", SubtitleSource.EMBEDDED),
        SubtitleOption("https://s/en", "English · OpenSubtitles", "eng", SubtitleSource.EXTERNAL),
        SubtitleOption("https://s/es", "Spanish · OpenSubtitles", "spa", SubtitleSource.EXTERNAL),
    )

    @Test
    fun `preferred subtitle language sorts to the top, embedded first`() {
        val ordered = TrackOrdering.subtitles(subs, "en", hideNonPreferred = false)
        assertEquals(listOf("0:1", "https://s/en", "0:0", "https://s/es"), ordered.map { it.id })
    }

    @Test
    fun `hide non-preferred languages leaves only the preferred ones`() {
        val ordered = TrackOrdering.subtitles(subs, "en", hideNonPreferred = true)
        assertEquals(listOf("0:1", "https://s/en"), ordered.map { it.id })
    }

    @Test
    fun `always on picks the first preferred track, always off picks none`() {
        val ordered = TrackOrdering.subtitles(subs, "en", hideNonPreferred = false)
        assertEquals("0:1", TrackOrdering.initialSubtitle(ordered, SubtitleMode.ALWAYS_ON, "en")?.id)
        assertNull(TrackOrdering.initialSubtitle(ordered, SubtitleMode.ALWAYS_OFF, "en"))
        assertNull(TrackOrdering.initialSubtitle(TrackOrdering.subtitles(subs, "ja", false), SubtitleMode.ALWAYS_ON, "ja"))
    }

    @Test
    fun `preferred audio language sorts first, regional tags included`() {
        val audio = listOf(AudioOption("0:0", "Spanish", "es"), AudioOption("1:0", "English", "en-US"), AudioOption("2:0", "Commentary", null))
        assertEquals(listOf("1:0", "0:0", "2:0"), TrackOrdering.audio(audio, "en").map { it.id })
        assertTrue(TrackOrdering.audio(audio, "de").map { it.id } == listOf("0:0", "1:0", "2:0"))
    }
}
