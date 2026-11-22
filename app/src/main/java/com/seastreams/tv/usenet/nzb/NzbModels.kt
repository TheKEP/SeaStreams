package com.seastreams.tv.usenet.nzb

import java.io.IOException

/** One article of a file: its 1-based [number] in the file, the size the NZB states, and the ID to ask for. */
data class NzbSegment(val number: Int, val bytes: Long, val messageId: String)

/** One posted file: its subject and its articles ordered by [NzbSegment.number], one per number. */
data class NzbFile(
    val subject: String,
    val poster: String,
    val groups: List<String>,
    val segments: List<NzbSegment>,
) {
    /** The filename from the subject (`"name.mkv" yEnc (1/50)`), if it names one. */
    val filename: String? by lazy { SubjectNames.filename(subject) }

    /** Σ stated bytes: usually the encoded size, a little over the real one. */
    val statedBytes: Long get() = segments.sumOf { it.bytes }
}

/**
 * A parsed NZB. [key] is the SHA-1 of its sorted, de-duplicated message IDs (`"<id>\n"` each), so the same
 * posting always has the same key whichever indexer served it. [password] comes from `<meta type="password">`
 * and must never be logged or stored on its own.
 */
class Nzb(
    val files: List<NzbFile>,
    val password: String?,
    val meta: Map<String, String>,
    val key: String,
) {
    override fun toString(): String = "Nzb(files=${files.size}, key=$key)"
}

/** The text is not a usable NZB. */
class NzbParseException(message: String) : IOException(message)
