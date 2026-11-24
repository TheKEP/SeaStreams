package com.seastreams.tv.domain.streams

import com.seastreams.tv.domain.streams.format.FormatterPreview
import com.seastreams.tv.domain.streams.format.FormatterTemplates
import com.seastreams.tv.domain.streams.format.TemplateRenderer
import com.seastreams.tv.domain.streams.format.tidyCardText
import com.seastreams.tv.ui.screens.playback.streamCardText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Spaces typed into custom formatter templates reach the stream card exactly as typed (PRD §13 *Custom templates*). */
class FormatterSpacesTest {

    private val values: Map<String, Map<String, Any?>> = mapOf(
        "stream" to mapOf(
            "resolution" to "2160p",
            "visualTags" to listOf("HDR10", "DV"),
            "releaseGroup" to null,
        ),
        "addon" to mapOf("name" to "Torrentio"),
    )

    private fun render(template: String) = TemplateRenderer.compile(template).render(values)

    private fun card(name: String, description: String) = FormatterPreview.render(FormatterTemplates(name, description), nowMillis = 0)

    @Test
    fun `spaces between tokens are kept, however many`() {
        assertEquals("2160p Torrentio", render("{stream.resolution} {addon.name}"))
        assertEquals("2160p   Torrentio", render("{stream.resolution}   {addon.name}"))
        assertEquals("[ 2160p ]", render("[ {stream.resolution} ]"))
    }

    @Test
    fun `spaces inside a conditional group render with it and vanish with it`() {
        assertEquals("a  2160p  b", render("a {? {stream.resolution} ?} b"))
        assertEquals("2160p   |", render("{?{stream.resolution}   ?}|"))
        // An absent field drops the group, spaces and all; the spaces outside it stay.
        assertEquals("a  b", render("a {?{stream.releaseGroup}   ?} b"))
    }

    @Test
    fun `spaces in check branches, join separators and prefixes are kept`() {
        assertEquals("  4K  |", render("""{stream.resolution::=2160p["  4K  "||""]}|"""))
        assertEquals("HDR10  ·  DV", render("{stream.visualTags::join('  ·  ')}"))
        assertEquals("Res:  2160p", render("Res:  {stream.resolution}"))
    }

    @Test
    fun `card text keeps leading and inner spaces and trims only line ends and empty lines`() {
        assertEquals("  4K   Torrentio", tidyCardText("  4K   Torrentio   "))
        assertEquals("a\n   b", tidyCardText("a  \n\n   \n   b\t"))
    }

    @Test
    fun `a custom name and description keep their spaces on the rendered stream`() {
        val display = card(
            name = "  {stream.resolution}   {addon.name}  ",
            description = "{stream.resolution} |  {addon.name}\n    indented line   \n{?{stream.resolution} ?}{addon.name}",
        )
        assertEquals("  2160p   Torrentio", display.name)
        assertEquals("2160p |  Torrentio\n    indented line\n2160p Torrentio", display.description)

        val text = streamCardText(FormatterPreview.SAMPLE.copy(display = display))
        assertEquals("  2160p   Torrentio", text.title)
        assertEquals(listOf("2160p |  Torrentio", "    indented line", "2160p Torrentio"), text.detailLines)
    }

    @Test
    fun `a multi-line name keeps each line's leading spaces`() {
        val display = card(name = "{addon.name}{tools.newLine}  {stream.resolution}", description = "")
        assertEquals("Torrentio\n  2160p", display.name)
    }
}
