package com.seastreams.tv.data.skip

import com.seastreams.tv.domain.playback.skip.MediaChapter

/**
 * Reads Matroska/WebM chapters from raw bytes (no library; ExoPlayer's extractor ignores chapters). The head of
 * the file is walked through `EBML` → `Segment` → its top-level children: `Chapters` found there is parsed at
 * once, otherwise `SeekHead` says where it lives ([HeadResult.At], an absolute file offset for a second read).
 * Malformed or truncated input yields [HeadResult.None] / no chapters, never an exception.
 */
object EbmlChapterParser {

    sealed interface HeadResult {
        data class Found(val chapters: List<MediaChapter>) : HeadResult

        data class At(val offset: Long) : HeadResult

        data object None : HeadResult
    }

    fun parseHead(bytes: ByteArray): HeadResult = try {
        readHead(Cursor(bytes))
    } catch (e: MalformedEbml) {
        HeadResult.None
    }

    /** [bytes] start at a `Chapters` element (the [HeadResult.At] offset). */
    fun parseChaptersAt(bytes: ByteArray): List<MediaChapter> = try {
        val cursor = Cursor(bytes)
        val header = cursor.header()
        if (header.id != CHAPTERS) emptyList() else chapters(cursor.slice(header.size))
    } catch (e: MalformedEbml) {
        emptyList()
    }

    private fun readHead(cursor: Cursor): HeadResult {
        val ebml = cursor.header()
        if (ebml.id != EBML) return HeadResult.None
        cursor.skip(ebml.size)
        val segment = cursor.header()
        if (segment.id != SEGMENT) return HeadResult.None
        val segmentStart = cursor.position.toLong()
        var seekChapters: Long? = null
        while (cursor.remaining > 0) {
            val child = try {
                cursor.header()
            } catch (e: MalformedEbml) {
                break
            }
            when {
                child.id == CHAPTERS && child.size >= 0 && child.size <= cursor.remaining ->
                    return HeadResult.Found(chapters(cursor.slice(child.size)))
                child.id == SEEK_HEAD && child.size >= 0 && child.size <= cursor.remaining ->
                    seekChapters = seekPosition(cursor.slice(child.size))
                // Clusters (the media) come after every header element that matters here.
                child.id == CLUSTER || child.size < 0 || child.size > cursor.remaining -> break
                else -> cursor.skip(child.size)
            }
        }
        return seekChapters?.let { HeadResult.At(segmentStart + it) } ?: HeadResult.None
    }

    /** SeekHead → the `Chapters` entry's position, relative to the Segment's data. */
    private fun seekPosition(seekHead: Cursor): Long? {
        while (seekHead.remaining > 0) {
            val seek = seekHead.header()
            if (seek.id != SEEK) {
                seekHead.skip(seek.size)
                continue
            }
            val entry = seekHead.slice(seek.size)
            var id: Long? = null
            var position: Long? = null
            while (entry.remaining > 0) {
                val field = entry.header()
                when (field.id) {
                    SEEK_ID -> id = entry.uint(field.size)
                    SEEK_POSITION -> position = entry.uint(field.size)
                    else -> entry.skip(field.size)
                }
            }
            if (id == CHAPTERS && position != null) return position
        }
        return null
    }

    /** The default edition's (else the first's) visible, enabled chapters. */
    private fun chapters(body: Cursor): List<MediaChapter> {
        val editions = mutableListOf<Pair<Boolean, List<MediaChapter>>>()
        while (body.remaining > 0) {
            val element = body.header()
            if (element.id == EDITION_ENTRY) editions += edition(body.slice(element.size)) else body.skip(element.size)
        }
        return (editions.firstOrNull { it.first } ?: editions.firstOrNull())?.second.orEmpty().sortedBy { it.startMs }
    }

    private fun edition(cursor: Cursor): Pair<Boolean, List<MediaChapter>> {
        var isDefault = false
        val atoms = mutableListOf<MediaChapter>()
        while (cursor.remaining > 0) {
            val element = cursor.header()
            when (element.id) {
                EDITION_FLAG_DEFAULT -> isDefault = cursor.uint(element.size) == 1L
                CHAPTER_ATOM -> atom(cursor.slice(element.size))?.let(atoms::add)
                else -> cursor.skip(element.size)
            }
        }
        return isDefault to atoms
    }

    private fun atom(cursor: Cursor): MediaChapter? {
        var startNs: Long? = null
        var title: String? = null
        var hidden = false
        var enabled = true
        while (cursor.remaining > 0) {
            val element = cursor.header()
            when (element.id) {
                CHAPTER_TIME_START -> startNs = cursor.uint(element.size)
                CHAPTER_FLAG_HIDDEN -> hidden = cursor.uint(element.size) == 1L
                CHAPTER_FLAG_ENABLED -> enabled = cursor.uint(element.size) != 0L
                CHAPTER_DISPLAY -> {
                    val display = cursor.slice(element.size)
                    while (display.remaining > 0) {
                        val field = display.header()
                        if (field.id == CHAP_STRING && title == null) title = display.string(field.size) else display.skip(field.size)
                    }
                }
                else -> cursor.skip(element.size)
            }
        }
        val start = startNs ?: return null
        if (hidden || !enabled) return null
        return MediaChapter(start / NANOS_PER_MILLI, title?.trim()?.ifEmpty { null })
    }

    private class MalformedEbml : Exception()

    private data class Header(val id: Long, val size: Long)

    private class Cursor(private val bytes: ByteArray, var position: Int = 0, private val end: Int = bytes.size) {
        val remaining: Int get() = end - position

        fun header(): Header {
            val id = vint(keepMarker = true)
            val size = vint(keepMarker = false)
            return Header(id, size)
        }

        fun skip(size: Long) {
            if (size < 0 || size > remaining) throw MalformedEbml()
            position += size.toInt()
        }

        fun slice(size: Long): Cursor {
            if (size < 0 || size > remaining) throw MalformedEbml()
            val child = Cursor(bytes, position, position + size.toInt())
            position += size.toInt()
            return child
        }

        fun uint(size: Long): Long {
            if (size !in 0..MAX_UINT_BYTES || size > remaining) throw MalformedEbml()
            var value = 0L
            repeat(size.toInt()) { value = (value shl BYTE_BITS) or byte().toLong() }
            return value
        }

        fun string(size: Long): String {
            if (size < 0 || size > remaining) throw MalformedEbml()
            val text = String(bytes, position, size.toInt(), Charsets.UTF_8).trimEnd('\u0000')
            position += size.toInt()
            return text
        }

        private fun byte(): Int {
            if (position >= end) throw MalformedEbml()
            return bytes[position++].toInt() and BYTE_MASK
        }

        /** An EBML variable-length integer; sizes of all `1` bits mean "unknown" (`-1`). */
        private fun vint(keepMarker: Boolean): Long {
            val first = byte()
            if (first == 0) throw MalformedEbml()
            val length = Integer.numberOfLeadingZeros(first) - (Int.SIZE_BITS - BYTE_BITS) + 1
            var value = if (keepMarker) first.toLong() else (first and (BYTE_MASK shr length)).toLong()
            var allOnes = value == (BYTE_MASK shr length).toLong()
            repeat(length - 1) {
                val next = byte()
                allOnes = allOnes && next == BYTE_MASK
                value = (value shl BYTE_BITS) or next.toLong()
            }
            return if (!keepMarker && allOnes) -1 else value
        }
    }

    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val CLUSTER = 0x1F43B675L
    private const val CHAPTERS = 0x1043A770L
    private const val EDITION_ENTRY = 0x45B9L
    private const val EDITION_FLAG_DEFAULT = 0x45DBL
    private const val CHAPTER_ATOM = 0xB6L
    private const val CHAPTER_TIME_START = 0x91L
    private const val CHAPTER_FLAG_HIDDEN = 0x98L
    private const val CHAPTER_FLAG_ENABLED = 0x4598L
    private const val CHAPTER_DISPLAY = 0x80L
    private const val CHAP_STRING = 0x85L
    private const val NANOS_PER_MILLI = 1_000_000L
    private const val MAX_UINT_BYTES = 8L
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xFF
}
