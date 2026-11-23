package com.seastreams.tv.domain.streams

import com.seastreams.tv.domain.streams.format.TemplateRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TemplateEngineTest {

    private val values: Map<String, Map<String, Any?>> = mapOf(
        "stream" to mapOf(
            "resolution" to "2160p",
            "size" to 1_500_000_000L,
            "seeders" to 0L,
            "visualTags" to listOf("HDR10", "DV"),
            "audioTags" to emptyList<String>(),
            "title" to "movie title",
            "releaseGroup" to null,
            "duration" to 5_400_000L,
            "proxied" to false,
        ),
        "addon" to mapOf("name" to "Torrentio"),
    )

    private fun render(template: String) = TemplateRenderer.compile(template).render(values)

    @Test
    fun `checks, comparisons and the absent branch`() {
        assertEquals("4K", render("""{stream.resolution::=2160p["4K"||"HD"]}"""))
        assertEquals("HD", render("""{stream.resolution::=1080P["4K"||"HD"]}"""))
        assertEquals("big", render("""{stream.size::>1000000000["big"||"small"]}"""))
        assertEquals("", render("""{stream.releaseGroup::exists["x"||""]}"""))
        assertEquals("none", render("""{stream.releaseGroup["yes"||"no"||"none"]}"""))
        assertEquals("seeded", render("""{stream.seeders::>=0["seeded"||""]}"""))
        assertEquals("has DV", render("""{stream.visualTags::~dv["has DV"||""]}"""))
    }

    @Test
    fun `modifiers`() {
        assertEquals("HDR10 | DV", render("{stream.visualTags::join(' | ')}"))
        assertEquals("HDR10,DV", render("{stream.visualTags}"))
        assertEquals("1.5 GB 1.4 GiB 2 GB", render("{stream.size::bytes} {stream.size::sbytes2} {stream.size::rbytes}"))
        assertEquals("1h:30m:0s 01:30", render("{stream.duration::time} {stream.duration::time('%H:%M')}"))
        assertEquals("Movie Title MOVIE TITLE", render("{stream.title::title} {stream.title::upper}"))
        assertEquals("✨ 4K", render("{stream.resolution::replace('2160p', '✨ 4K')}"))
        assertEquals("movie…", render("{stream.title::truncate(5)}"))
        assertEquals("N/A", render("{stream.releaseGroup::default('N/A')}"))
        assertEquals("DV", render("{stream.visualTags::remove('HDR10')}"))
        assertEquals("{unknown_string_modifier(bytes)}", render("{stream.title::bytes}"))
    }

    @Test
    fun `groups vanish when a value is missing`() {
        assertEquals("[Torrentio]", render("{?[{addon.name}]?}{?({stream.releaseGroup})?}"))
        assertEquals("", render("{?🎧 {stream.audioTags::join(' | ')}?}"))
    }

    @Test
    fun `comparators, newLine and removeLine`() {
        assertEquals("both", render("""{stream.size::>0::and::stream.visualTags::exists["both"||"no"]}"""))
        assertEquals("no", render("""{stream.proxied::istrue::or::stream.seeders::>5["yes"||"no"]}"""))
        assertEquals("a\nb", render("a{tools.newLine}b"))
        assertEquals("keep", render("keep\ndrop{tools.removeLine}"))
        assertEquals("x", render("x\n\n   \n"))
    }

    @Test
    fun `bad syntax stays visible`() {
        assertEquals("{invalid_expression(stream.unknownField)}", render("{stream.unknownField}"))
        assertEquals("{not a field}", render("{not a field}"))
        assertEquals("{cannot_coerce_boolean_for_check_from(2160p)}", render("""{stream.resolution["a"||"b"]}"""))
    }
}
