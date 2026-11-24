package com.seastreams.tv.ui.screens.player

import androidx.compose.ui.unit.dp
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** PRD §9 Info overlay: row order, the estimated bitrate, and the card's reach down to the seek row. */
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
            listOf("Stream", "Player engine", "Download", "Range connections", "Buffer", "File", "Video codec", "Dynamic range", "Resolution", "Bitrate"),
            labels.take(10),
        )
        assertEquals(listOf("Audio codec", "Channels", "Sample rate", "Audio language", "Subtitles"), labels.drop(10).take(5))
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
        assertEquals(listOf("Stream", "Player engine", "Download", "Buffer", "File"), labels.take(5))
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

    // ---- Panel geometry -------------------------------------------------------------------------

    @Test
    fun `the Info card reaches lower than the other panels, yet stays above the seek row`() {
        assertTrue(INFO_PANEL_BOTTOM_INSET < PANEL_BOTTOM_INSET, "Info extends below the other panels")
        // Bottom padding + action bar + spacing + seek row: the card never covers the remaining-time text.
        assertTrue(INFO_PANEL_BOTTOM_INSET >= HUD_BOTTOM_PADDING + CIRCLE_SIZE + 14.dp + 24.dp + INFO_PANEL_GAP)
        assertEquals(INFO_PANEL_BOTTOM_INSET, infoPanelBottomInset(null))
    }

    @Test
    fun `a measured HUD block places the card a clean gap above its seek row`() {
        // 24 padding + 20 seek row + 14 + 48 bar + 24 padding.
        assertEquals(130.dp - HUD_BOTTOM_PADDING + INFO_PANEL_GAP, infoPanelBottomInset(130.dp))
        // A larger font scale (taller time label) lifts the card with it.
        assertTrue(infoPanelBottomInset(160.dp) > infoPanelBottomInset(130.dp))
        assertEquals(INFO_PANEL_BOTTOM_INSET, infoPanelBottomInset(0.dp), "an unmeasured (zero) block falls back")
    }

    @Test
    fun `the scrollbar thumb is proportional and follows the scroll position`() {
        assertNull(scrollbarThumb(0, maxValue = 0, viewport = 400, trackPx = 400f, minThumbPx = 24f), "no overflow, no scrollbar")
        val top = scrollbarThumb(0, maxValue = 400, viewport = 400, trackPx = 400f, minThumbPx = 24f)
        assertEquals(ThumbSpan(0f, 200f), top, "half the content visible: half-length thumb at the top")
        assertEquals(ThumbSpan(100f, 200f), scrollbarThumb(200, 400, 400, 400f, 24f))
        assertEquals(ThumbSpan(200f, 200f), scrollbarThumb(400, 400, 400, 400f, 24f))
        val long = scrollbarThumb(0, maxValue = 100_000, viewport = 400, trackPx = 400f, minThumbPx = 24f)
        assertEquals(24f, long?.length, "a very long list keeps a visible thumb")
    }
}
