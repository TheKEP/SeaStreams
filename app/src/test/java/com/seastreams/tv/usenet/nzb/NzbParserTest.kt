package com.seastreams.tv.usenet.nzb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NzbParserTest {

    private fun nzb(head: String = "", vararg files: String) = """
        <?xml version="1.0" encoding="iso-8859-1" ?>
        <!DOCTYPE nzb PUBLIC "-//newzBin//DTD NZB 1.1//EN" "http://www.newzbin.com/DTD/nzb/nzb-1.1.dtd">
        <nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">
        <head>$head</head>
        ${files.joinToString("\n")}
        </nzb>
    """.trimIndent()

    private fun file(subject: String, vararg segments: String) = """
        <file poster="poster@x" date="1700000000" subject="$subject">
          <groups><group>alt.binaries.test</group><group>alt.binaries.more</group></groups>
          <segments>${segments.joinToString("")}</segments>
        </file>
    """.trimIndent()

    private fun seg(number: Int?, id: String, bytes: Long = 700_000) =
        "<segment bytes=\"$bytes\"${number?.let { " number=\"$it\"" } ?: ""}>$id</segment>"

    @Test
    fun `reads files, groups and segments in number order`() {
        val parsed = NzbParser.parse(nzb("", file("&quot;Movie.2024.mkv&quot; yEnc (1/3)", seg(3, "c@x"), seg(1, "a@x"), seg(2, "b@x"))))
        val only = parsed.files.single()
        assertEquals(listOf("a@x", "b@x", "c@x"), only.segments.map { it.messageId })
        assertEquals("Movie.2024.mkv", only.filename)
        assertEquals(listOf("alt.binaries.test", "alt.binaries.more"), only.groups)
        assertEquals("poster@x", only.poster)
        assertEquals(2_100_000L, only.statedBytes)
    }

    @Test
    fun `the message-ID guard drops IDs that could break a command line`() {
        val parsed = NzbParser.parse(
            nzb(
                "",
                file(
                    "x.bin",
                    seg(1, "&lt;ok@x&gt;"),
                    seg(2, "bad id@x"),
                    seg(3, "evil@x&#13;&#10;QUIT"),
                    seg(4, "a".repeat(251)),
                    seg(5, "café@x"),
                    seg(6, ""),
                    seg(7, "fine@x"),
                ),
            ),
        )
        assertEquals(listOf("ok@x", "fine@x"), parsed.files.single().segments.map { it.messageId })
    }

    @Test
    fun `missing numbers are backfilled by document order and duplicates keep the first`() {
        val parsed = NzbParser.parse(nzb("", file("x.bin", seg(null, "a@x"), seg(null, "b@x"), seg(2, "dup@x"), seg(3, "c@x"))))
        val segments = parsed.files.single().segments
        assertEquals(listOf(1, 2, 3), segments.map { it.number })
        assertEquals("a@x", segments[0].messageId)
        assertEquals(3, segments.size)
    }

    @Test
    fun `one file per article is merged into one file per name`() {
        val parsed = NzbParser.parse(
            nzb(
                "",
                file("&quot;big.mkv&quot; yEnc (2/3)", seg(1, "b@x")),
                file("&quot;big.mkv&quot; yEnc (1/3)", seg(1, "a@x")),
                file("&quot;big.mkv&quot; yEnc (3/3)", seg(1, "c@x")),
                file("&quot;other.nfo&quot; yEnc (1/1)", seg(1, "n@x")),
            ),
        )
        assertEquals(2, parsed.files.size)
        val merged = parsed.files.first { it.filename == "big.mkv" }
        assertEquals(listOf("a@x", "b@x", "c@x"), merged.segments.map { it.messageId })
        assertEquals(listOf(1, 2, 3), merged.segments.map { it.number })
    }

    @Test
    fun `an incomplete run is not merged`() {
        val parsed = NzbParser.parse(nzb("", file("&quot;a.mkv&quot; (1/3)", seg(1, "a@x")), file("&quot;a.mkv&quot; (3/3)", seg(1, "c@x"))))
        assertEquals(2, parsed.files.size)
    }

    @Test
    fun `password meta in the head, CDATA and single quotes`() {
        val head = "<meta type='title'>Movie</meta><meta type=\"password\"><![CDATA[s3cr&t]]></meta>"
        val parsed = NzbParser.parse(nzb(head, file("x.bin", seg(1, "a@x"))))
        assertEquals("s3cr&t", parsed.password)
        assertEquals("Movie", parsed.meta["title"])
        val escaped = NzbParser.parse(nzb("<meta type=\"pwd\">a&amp;b</meta>", file("x.bin", seg(1, "a@x"))))
        assertEquals("a&b", escaped.password)
        assertNull(NzbParser.parse(nzb("", file("x.bin", seg(1, "a@x")))).password)
    }

    @Test
    fun `the key is the SHA-1 of the sorted IDs, independent of order and formatting`() {
        val one = NzbParser.parse(nzb("", file("x.bin", seg(1, "a@x"), seg(2, "b@x"))))
        val reordered = NzbParser.parse(nzb("<meta type=\"title\">t</meta>", file("other", seg(2, "b@x"), seg(1, "a@x"))))
        val different = NzbParser.parse(nzb("", file("x.bin", seg(1, "a@x"), seg(2, "z@x"))))
        assertEquals(one.key, reordered.key)
        assertNotEquals(one.key, different.key)
        // sha1("a@x\nb@x\n")
        assertEquals(40, one.key.length)
    }

    @Test
    fun `segments-like tags and files without articles are ignored, and an empty NZB is refused`() {
        val parsed = NzbParser.parse(nzb("", file("empty.bin"), file("x.bin", seg(1, "a@x"))))
        assertEquals(1, parsed.files.size)
        assertThrows<NzbParseException> { NzbParser.parse(nzb("", file("empty.bin"))) }
        assertThrows<NzbParseException> { NzbParser.parse("not xml at all") }
    }

    @Test
    fun `subject names`() {
        assertEquals("My.File.mkv", SubjectNames.filename("[1/8] - \"My.File.mkv\" yEnc (1/120)"))
        assertEquals("file.nfo", SubjectNames.filename("file.nfo (1/1)"))
        assertEquals(120, SubjectNames.partNumber("[1/8] - \"My.File.mkv\" yEnc (120/120)"))
        assertNull(SubjectNames.filename(""))
    }
}
