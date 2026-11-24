package com.seastreams.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.StreamHints
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.testutil.testDetails
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** PRD §9 Info overlay: row order, the file size and the estimated bitrate (card geometry: PlayerPanelScrollbarTest). */
class PlayerInfoRowsTest {

    private val http = StreamSource("https://addon", "Addon", "https://cdn/Movie.mkv", "Addon · 4k", "Movie.2024.2160p.mkv", emptyMap())
    private val usenet = http.copy(url = UsenetStreamRegistry.SCHEME_PREFIX + "0123456789abcdef", label = "Usenet · 1080p")

    private fun state(stream: StreamSource, runtimeMinutes: Int? = 120): PlayerUiState {
        val request = PlaybackRequest.Media(1, PlaybackTarget(1, testDetails(7, runtimeMinutes = runtimeMinutes), "tt7", null), listOf(stream), 0, emptyList(), 0)
        return PlayerUiState(request = request, streams = listOf(stream), streamIndex = 0)
    }

    private val telemetry = InfoTelemetry(downloadMbps = 48.25, activeConnections = 3, maxConnections = 4, bufferedSeconds = 32.0, bufferedMb = 120.0)

    @Test
    fun `live network rows sit directly below Player engine and above File`() {
        val labels = infoRows(state(http), telemetry).map { it.label }
        assertEquals(
            listOf("Stream", "Player engine", "Download", "Range connections", "Buffer", "File", "File size", "Video codec", "Dynamic range", "Resolution", "Bitrate"),
            labels.take(11),
        )
        assertEquals(listOf("Audio codec", "Channels", "Sample rate", "Audio language", "Subtitles"), labels.drop(11).take(5))
    }

    @Test
    fun `network rows show live values`() {
        val rows = infoRows(state(http), telemetry).associate { it.label to it.value }
        assertEquals("48.3 Mbps", rows["Download"])
        assertEquals("3x active · 4x max", rows["Range connections"])
        assertEquals("32.0 s · 120.0 MB", rows["Buffer"])
        assertEquals("Movie.2024.2160p.mkv", rows["File"])
    }

    @Test
    fun `Usenet releases have no HTTP range row, the rest keep their order`() {
        val labels = infoRows(state(usenet), telemetry).map { it.label }
        assertFalse("Range connections" in labels)
        assertEquals(listOf("Stream", "Player engine", "Download", "Buffer", "File", "File size"), labels.take(6))
    }

    @Test
    fun `File size reads 1024-based units, and a dash when unknown`() {
        assertEquals("58.42 GB", fileSizeText(62_727_000_000))
        assertEquals("1.00 GB", fileSizeText(1L shl 30))
        assertEquals("734 MB", fileSizeText(734L * 1024 * 1024))
        assertEquals("512 KB", fileSizeText(512L * 1024))
        assertEquals("900 B", fileSizeText(900))
        assertEquals("—", fileSizeText(null))
        assertEquals("—", fileSizeText(0))
        assertEquals("—", fileSizeText(-1))
    }

    @Test
    fun `the File size row uses the tracked size, then the hint, then the parsed release, like Bitrate`() {
        fun size(stream: StreamSource, tracked: Long?) =
            infoRows(state(stream), telemetry.copy(contentLengthBytes = tracked)).first { it.label == "File size" }.value
        val hinted = http.copy(hints = StreamHints(sizeBytes = 4L shl 30))
        assertEquals("9.00 GB", size(hinted, tracked = 9L shl 30), "the data source's own length wins")
        assertEquals("4.00 GB", size(hinted, tracked = null))
        assertEquals("4.00 GB", size(hinted, tracked = 0))
        assertEquals("—", size(http, tracked = null), "nothing known")
    }

    @Test
    fun `a track-reported bitrate is shown as is`() {
        val video = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setAverageBitrate(18_400_000).build()
        assertEquals("18.4 Mbps", bitrateText(video, sizeBytes = 50_000_000_000, durationMs = 7_200_000))
    }

    @Test
    fun `no track bitrate - estimated from file size and duration, and marked as an estimate`() {
        val video = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build()
        // 10 GiB over 2 h = 10 737 418 240 × 8 ÷ 7 200 s ≈ 11.93 Mbps.
        assertEquals("11.9 Mbps (est.)", bitrateText(video, sizeBytes = 10L * 1024 * 1024 * 1024, durationMs = 7_200_000))
        assertEquals("11.9 Mbps (est.)", bitrateText(null, sizeBytes = 10L * 1024 * 1024 * 1024, durationMs = 7_200_000))
    }

    @Test
    fun `an unknown size or duration leaves the row blank`() {
        assertEquals("—", bitrateText(null, sizeBytes = null, durationMs = 7_200_000))
        assertEquals("—", bitrateText(null, sizeBytes = 0, durationMs = 7_200_000))
        assertEquals("—", bitrateText(null, sizeBytes = 1_000_000, durationMs = 0))
        assertNull(estimateBitsPerSecond(1_000_000, -1))
    }

    @Test
    fun `size comes from the data source first, then the addon or indexer hint, then the parsed release`() {
        val hinted = http.copy(hints = StreamHints(sizeBytes = 4_000_000_000))
        assertEquals(9_000_000_000, streamSizeBytes(9_000_000_000, hinted))
        assertEquals(4_000_000_000, streamSizeBytes(null, hinted))
        assertEquals(4_000_000_000, streamSizeBytes(0, hinted))
        assertNull(streamSizeBytes(null, http))
    }

    @Test
    fun `duration is ExoPlayer's once known, else the TMDB runtime`() {
        assertEquals(5_000_000, playbackDurationMs(5_000_000, 7_200_000))
        assertEquals(7_200_000, playbackDurationMs(-9_223_372_036_854_775_807, 7_200_000))
        assertEquals(0, playbackDurationMs(0, 0))
    }

    @Test
    fun `the Bitrate row estimates from the tracked file size and the TMDB runtime before ExoPlayer knows the duration`() {
        val rows = infoRows(state(http, runtimeMinutes = 120), telemetry.copy(contentLengthBytes = 9_000_000_000, playerDurationMs = -1)).associate { it.label to it.value }
        assertEquals("10.0 Mbps (est.)", rows["Bitrate"])
        val known = infoRows(state(http, runtimeMinutes = null), telemetry.copy(contentLengthBytes = 9_000_000_000, playerDurationMs = 3_600_000))
        assertEquals("20.0 Mbps (est.)", known.first { it.label == "Bitrate" }.value)
    }
}
