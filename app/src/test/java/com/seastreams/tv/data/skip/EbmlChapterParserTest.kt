package com.seastreams.tv.data.skip

import com.seastreams.tv.data.skip.EbmlChapterParser.HeadResult
import com.seastreams.tv.domain.playback.skip.MediaChapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/** Milestone 14: Matroska chapters read from synthetic EBML bytes. */
class EbmlChapterParserTest {

    // ---- A tiny EBML writer ------------------------------------------------------------------------

    private fun id(value: Long): ByteArray {
        val length = when {
            value <= 0xFF -> 1
            value <= 0xFFFF -> 2
            value <= 0xFFFFFF -> 3
            else -> 4
        }
        return ByteArray(length) { ((value shr (8 * (length - 1 - it))) and 0xFF).toByte() }
    }

    /** Sizes always as 8-byte vints, so a parent's size never depends on its children's encoding. */
    private fun size(value: Long): ByteArray = ByteArray(8) { i -> if (i == 0) 0x01 else ((value shr (8 * (7 - i))) and 0xFF).toByte() }

    private fun el(elementId: Long, vararg children: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { children.forEach(::write) }.toByteArray()
        return id(elementId) + size(body.size.toLong()) + body
    }

    private fun uint(elementId: Long, value: Long): ByteArray = el(elementId, ByteArray(8) { ((value shr (8 * (7 - it))) and 0xFF).toByte() })

    private fun str(elementId: Long, value: String): ByteArray = el(elementId, value.toByteArray())

    private fun atom(startMs: Long, title: String, hidden: Boolean = false, enabled: Boolean = true) = el(
        0xB6,
        uint(0x91, startMs * 1_000_000),
        uint(0x98, if (hidden) 1 else 0),
        uint(0x4598, if (enabled) 1 else 0),
        el(0x80, str(0x85, title), str(0x437C, "eng")),
    )

    private fun chapters(vararg editions: ByteArray) = el(0x1043A770, *editions)

    private fun edition(default: Boolean, vararg atoms: ByteArray) = el(0x45B9, uint(0x45DB, if (default) 1 else 0), *atoms)

    private val header = el(0x1A45DFA3, str(0x4282, "matroska"))
    private val info = el(0x1549A966, uint(0x2AD7B1, 1_000_000))
    private val cluster = el(0x1F43B675, uint(0xE7, 0))

    /** A Segment of unknown size (as live muxers write it), holding [children] in order. */
    private fun segment(vararg children: ByteArray): ByteArray =
        id(0x18538067) + byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) +
            ByteArrayOutputStream().apply { children.forEach(::write) }.toByteArray()

    // ---- Tests -------------------------------------------------------------------------------------

    @Test
    fun `chapters in the head are read, hidden and disabled atoms skipped`() {
        val file = header + segment(
            info,
            chapters(edition(true, atom(0, "Prologue"), atom(60_000, "Intro"), atom(90_000, "Secret", hidden = true), atom(150_000, "Off", enabled = false))),
            cluster,
        )

        assertEquals(HeadResult.Found(listOf(MediaChapter(0, "Prologue"), MediaChapter(60_000, "Intro"))), EbmlChapterParser.parseHead(file))
    }

    @Test
    fun `the default edition wins over the first`() {
        val file = header + segment(chapters(edition(false, atom(0, "A")), edition(true, atom(5_000, "B"))))

        assertEquals(HeadResult.Found(listOf(MediaChapter(5_000, "B"))), EbmlChapterParser.parseHead(file))
    }

    @Test
    fun `SeekHead points to chapters past the clusters, read at the absolute offset`() {
        val chapterBlock = chapters(edition(true, atom(0, "OP"), atom(90_000, "Part A")))
        fun seekHead(position: Long) = el(0x114D9B74, el(0x4DBB, el(0x53AB, id(0x1043A770)), uint(0x53AC, position)))
        // The SeekHead's own size doesn't depend on the position (fixed-width uint), so measure with 0 first.
        val relative = (seekHead(0).size + info.size + cluster.size).toLong()
        val file = header + segment(seekHead(relative), info, cluster, chapterBlock)
        val segmentDataStart = header.size + 4 + 8

        val result = EbmlChapterParser.parseHead(file.copyOf(header.size + 12 + seekHead(0).size + info.size + cluster.size))

        assertEquals(HeadResult.At(segmentDataStart + relative), result)
        val offset = (result as HeadResult.At).offset.toInt()
        assertEquals(
            listOf(MediaChapter(0, "OP"), MediaChapter(90_000, "Part A")),
            EbmlChapterParser.parseChaptersAt(file.copyOfRange(offset, file.size)),
        )
    }

    @Test
    fun `non-Matroska, truncated and garbage input yield nothing`() {
        val full = header + segment(info, chapters(edition(true, atom(0, "OP"))))

        assertEquals(HeadResult.None, EbmlChapterParser.parseHead(ByteArray(64) { 0x47 }))
        assertEquals(HeadResult.None, EbmlChapterParser.parseHead(full.copyOf(full.size - 10)))
        assertEquals(HeadResult.None, EbmlChapterParser.parseHead(ByteArray(0)))
        assertEquals(emptyList<MediaChapter>(), EbmlChapterParser.parseChaptersAt(cluster))
        assertEquals(emptyList<MediaChapter>(), EbmlChapterParser.parseChaptersAt(chapters(edition(true, atom(0, "OP"))).copyOf(20)))
    }
}
