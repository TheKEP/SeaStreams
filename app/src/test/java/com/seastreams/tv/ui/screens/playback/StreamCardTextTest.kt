package com.seastreams.tv.ui.screens.playback

import com.seastreams.tv.domain.model.StreamSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Stream result cards must show every addon line in full, whatever its length or count. */
class StreamCardTextTest {

    @Test
    fun `a long mkv filename keeps the audio and size line after it`() {
        val lines = listOf(
            "🎞️ Dune.Part.Two.2024.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.Atmos.7.1-FRAMESTOR.mkv",
            "📺 4K · DV · HDR10 · REMUX",
            "🎧 DD+ 5.1 | 💾 18.4 GB | 👤 412 | ⚙️ Torrentio",
        )
        val text = streamCardText(stream(description = lines.joinToString("\n")))

        assertEquals(lines, text.detailLines)
        assertEquals("🎧 DD+ 5.1 | 💾 18.4 GB | 👤 412 | ⚙️ Torrentio", text.detailLines.last())
        assertFalse(text.detailLines.any { it.contains('…') })
    }

    @Test
    fun `more than three description lines are all kept`() {
        val lines = (1..6).map { "Line $it with metadata" }
        assertEquals(lines, streamCardText(stream(description = lines.joinToString("\n"))).detailLines)
    }

    @Test
    fun `blank lines and CRLF endings are dropped without merging neighbours`() {
        val text = streamCardText(stream(description = "File.mkv  \r\n\r\n   \r\nDD+ 5.1 | 4 GB\r\n"))
        assertEquals(listOf("File.mkv", "DD+ 5.1 | 4 GB"), text.detailLines)
    }

    @Test
    fun `rank prefixes the title and an empty description has no detail lines`() {
        val text = streamCardText(stream(label = "AIOStreams · 4K", description = ""), rank = 3)
        assertEquals("#3  AIOStreams · 4K", text.title)
        assertEquals("Addon", text.header)
        assertTrue(text.detailLines.isEmpty())
    }

    @Test
    fun `a very long label is never shortened`() {
        val label = "AIOStreams · " + "4K HDR DV Atmos ".repeat(15)
        assertEquals(label, streamCardText(stream(label = label)).title)
    }

    private fun stream(label: String = "Addon · 1080p", description: String = "") =
        StreamSource("https://addon", "Addon", "https://s/1", label, description, emptyMap())
}
