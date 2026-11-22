package com.seastreams.tv.usenet.nzb

import com.seastreams.tv.core.util.toHex
import java.security.MessageDigest

/**
 * Reads the NZB fields streaming needs by walking the text with `indexOf` (ARCHITECTURE §10): no XML parser
 * (which would try to fetch the DTD an NZB names) and no whole-text regex (a 10 MB NZB must not be copied).
 * - Message IDs are unescaped, stripped of `<>` and refused if empty, over 250 chars, or holding a space,
 *   control, `<`/`>` or non-ASCII character: they go on an NNTP command line.
 * - Missing `number`s are backfilled by document order; duplicate numbers keep the first.
 * - NZBs that wrap every article in its own `<file>` are merged back into one file per name when the
 *   single-article files form a complete `1..N` run (by `number`, else by the subject's `(n/m)` counter).
 * - `<meta type="password">` is read from the head only.
 */
object NzbParser {
    const val MAX_MESSAGE_ID = 250
    private const val MAX_PASSWORD = 512

    fun parse(text: String): Nzb {
        val scanned = ArrayList<ScannedFile>()
        var at = findTag(text, "file", 0)
        val firstFile = at
        while (at >= 0) {
            val tagEnd = text.indexOf('>', at)
            if (tagEnd < 0) break
            val close = text.indexOf("</file>", tagEnd).let { if (it < 0) text.length else it }
            val segments = segments(text, tagEnd, close)
            if (segments.isNotEmpty()) {
                scanned += ScannedFile(
                    subject = attribute(text, at, tagEnd, "subject").orEmpty(),
                    poster = attribute(text, at, tagEnd, "poster").orEmpty(),
                    groups = groups(text, tagEnd, close),
                    segments = segments,
                )
            }
            at = findTag(text, "file", close)
        }
        val files = mergeSegmentPerFile(scanned).map(::build)
        if (files.isEmpty()) throw NzbParseException("The NZB lists no files with articles")
        val headEnd = if (firstFile < 0) text.length else firstFile
        val meta = meta(text, headEnd)
        return Nzb(files, meta["password"], meta, key(files))
    }

    private class ScannedFile(val subject: String, val poster: String, val groups: List<String>, val segments: MutableList<RawSegment>)

    private class RawSegment(var number: Int, val bytes: Long, val messageId: String)

    private fun segments(text: String, from: Int, to: Int): MutableList<RawSegment> {
        val out = ArrayList<RawSegment>()
        var scan = from
        while (true) {
            val open = findTag(text, "segment", scan)
            if (open < 0 || open >= to) break
            val end = text.indexOf('>', open)
            if (end < 0 || end >= to) break
            val next = text.indexOf('<', end + 1)
            if (next < 0 || next > to) break
            scan = next
            val id = unescape(text.substring(end + 1, next).trim()).removePrefix("<").removeSuffix(">")
            if (!validMessageId(id)) continue
            val number = attribute(text, open, end, "number")?.trim()?.toIntOrNull() ?: 0
            val bytes = attribute(text, open, end, "bytes")?.trim()?.toLongOrNull() ?: 0L
            out += RawSegment(number, bytes.coerceAtLeast(0), id)
        }
        return out
    }

    fun validMessageId(id: String): Boolean =
        id.isNotEmpty() && id.length <= MAX_MESSAGE_ID && id.none { it <= ' ' || it == '<' || it == '>' || it.code > 126 }

    private fun groups(text: String, from: Int, to: Int): List<String> {
        val out = ArrayList<String>()
        var scan = from
        while (true) {
            val open = findTag(text, "group", scan)
            if (open < 0 || open >= to) break
            val end = text.indexOf('>', open)
            val next = if (end < 0) -1 else text.indexOf('<', end + 1)
            if (end < 0 || next < 0 || next > to) break
            unescape(text.substring(end + 1, next).trim()).takeIf { it.isNotEmpty() }?.let(out::add)
            scan = next
        }
        return out
    }

    private fun build(file: ScannedFile): NzbFile {
        file.segments.forEachIndexed { index, segment -> if (segment.number <= 0) segment.number = index + 1 }
        val ordered = file.segments.sortedBy { it.number }
        val unique = ArrayList<NzbSegment>(ordered.size)
        for (segment in ordered) {
            if (unique.isEmpty() || unique.last().number != segment.number) unique += NzbSegment(segment.number, segment.bytes, segment.messageId)
        }
        return NzbFile(file.subject, file.poster, file.groups, unique)
    }

    private fun mergeSegmentPerFile(files: List<ScannedFile>): List<ScannedFile> {
        if (files.size < 2) return files
        val byName = LinkedHashMap<String, MutableList<ScannedFile>>()
        for (file in files) {
            if (file.segments.size != 1) continue
            val name = SubjectNames.filename(file.subject) ?: continue
            byName.getOrPut(name) { ArrayList() } += file
        }
        val merged = HashMap<ScannedFile, ScannedFile>()
        val absorbed = HashSet<ScannedFile>()
        for (group in byName.values) {
            if (group.size < 2) continue
            val run = completeRun(group) ?: continue
            val head = run.first()
            merged[head] = ScannedFile(head.subject, head.poster, run.flatMap { it.groups }.distinct(), run.mapTo(ArrayList()) { it.segments[0] })
            absorbed += run.drop(1)
        }
        if (merged.isEmpty()) return files
        return files.filter { it !in absorbed }.map { merged[it] ?: it }
    }

    /** Orders single-article files by part number, only when the numbers are exactly `1..N`. */
    private fun completeRun(group: List<ScannedFile>): List<ScannedFile>? {
        for (useSubject in listOf(false, true)) {
            val parts = group.map { if (useSubject) SubjectNames.partNumber(it.subject) else it.segments[0].number.takeIf { n -> n > 0 } }
            if (parts.any { it == null }) continue
            val numbers = parts.filterNotNull()
            if (numbers.toSet() != (1..group.size).toSet()) continue
            val keyed = group.zip(numbers).sortedBy { it.second }
            keyed.forEach { (file, part) -> file.segments[0].number = part }
            return keyed.map { it.first }
        }
        return null
    }

    private fun meta(text: String, headEnd: Int): Map<String, String> {
        val limit = minOf(headEnd, text.length, 64 * 1024)
        val out = LinkedHashMap<String, String>()
        var scan = 0
        while (true) {
            val open = findTag(text, "meta", scan)
            if (open < 0 || open >= limit) break
            val end = text.indexOf('>', open)
            if (end < 0 || end >= limit) break
            val close = text.indexOf("</meta", end).takeIf { it in 0 until limit } ?: break
            scan = close
            val type = attribute(text, open, end, "type")?.trim()?.lowercase() ?: continue
            val raw = text.substring(end + 1, close).trim()
            val value = if (raw.startsWith("<![CDATA[") && raw.endsWith("]]>")) raw.substring(9, raw.length - 3) else unescape(raw)
            if (value.isEmpty() || value.length > MAX_PASSWORD) continue
            val key = if (type == "pass" || type == "pwd") "password" else type
            out.putIfAbsent(key, value)
        }
        return out
    }

    private fun key(files: List<NzbFile>): String {
        val digest = MessageDigest.getInstance("SHA-1")
        files.flatMap { file -> file.segments.map { it.messageId } }.toSortedSet().forEach { digest.update("$it\n".toByteArray()) }
        return digest.digest().toHex()
    }

    /** `<name` followed by whitespace, `/` or `>` (so `<segments>` never matches `segment`). */
    private fun findTag(text: String, name: String, from: Int): Int {
        var at = text.indexOf("<$name", from)
        while (at >= 0) {
            val after = at + name.length + 1
            if (after < text.length && (text[after].isWhitespace() || text[after] == '>' || text[after] == '/')) return at
            at = text.indexOf("<$name", after)
        }
        return -1
    }

    /** [name]'s value inside the tag [from]..[to], single or double quoted; never searches past the tag. */
    private fun attribute(text: String, from: Int, to: Int, name: String): String? {
        var at = from
        while (true) {
            at = text.indexOf(name, at)
            if (at < 0 || at >= to) return null
            val before = text[at - 1]
            var eq = at + name.length
            while (eq < to && text[eq].isWhitespace()) eq++
            if (before.isWhitespace() && eq < to && text[eq] == '=') {
                var quote = eq + 1
                while (quote < to && text[quote].isWhitespace()) quote++
                if (quote < to && (text[quote] == '"' || text[quote] == '\'')) {
                    val end = text.indexOf(text[quote], quote + 1)
                    return if (end < 0 || end > to) null else unescape(text.substring(quote + 1, end))
                }
            }
            at += name.length
        }
    }

    internal fun unescape(value: String): String {
        if ('&' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val semi = if (c == '&') value.indexOf(';', i) else -1
            if (semi < 0 || semi - i > 10) {
                out.append(c)
                i++
                continue
            }
            val decoded = entity(value.substring(i + 1, semi))
            if (decoded == null) out.append(c).also { i++ } else out.append(decoded).also { i = semi + 1 }
        }
        return out.toString()
    }

    private fun entity(name: String): String? = when {
        name == "lt" -> "<"
        name == "gt" -> ">"
        name == "quot" -> "\""
        name == "apos" -> "'"
        name == "amp" -> "&"
        name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)?.let(::codePoint)
        name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::codePoint)
        else -> null
    }

    private fun codePoint(code: Int): String? =
        if (Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF) String(Character.toChars(code)) else null
}
