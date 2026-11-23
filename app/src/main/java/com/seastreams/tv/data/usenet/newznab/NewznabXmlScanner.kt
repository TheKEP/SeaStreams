package com.seastreams.tv.data.usenet.newznab

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Reads Newznab RSS / caps responses by walking the text with `indexOf` (ARCHITECTURE §10): no XML parser, so
 * no DTD or external entity is ever fetched, and malformed items are skipped rather than failing the page.
 * Handles `newznab:` / `torznab:` / bare `attr` elements, CDATA and the five XML entities plus numeric ones.
 */
object NewznabXmlScanner {

    /** The `<error code=… description=…/>` an indexer answers instead of results, if any. */
    fun error(xml: String): NewznabException? {
        val at = findTag(xml, "error", 0)
        if (at < 0) return null
        val end = xml.indexOf('>', at).takeIf { it > 0 } ?: return null
        val code = attribute(xml, at, end, "code")?.trim()?.toIntOrNull() ?: return null
        return NewznabException.fromCode(code, attribute(xml, at, end, "description"))
    }

    fun items(xml: String): List<NewznabItem> {
        val out = ArrayList<NewznabItem>()
        var at = findTag(xml, "item", 0)
        while (at >= 0) {
            val close = xml.indexOf("</item>", at).let { if (it < 0) xml.length else it }
            item(xml, at, close)?.let(out::add)
            at = findTag(xml, "item", close)
        }
        return out
    }

    fun caps(xml: String): NewznabCaps {
        val searching = section(xml, "searching")
        val categories = HashSet<Int>()
        section(xml, "categories")?.let { (from, to) ->
            var at = findTag(xml, "category", from)
            while (at in 0 until to) {
                val end = xml.indexOf('>', at)
                if (end < 0) break
                attribute(xml, at, end, "id")?.trim()?.toIntOrNull()?.let(categories::add)
                at = findTag(xml, "category", end)
            }
        }
        fun mode(tag: String, default: NewznabSearchMode): NewznabSearchMode {
            val (from, to) = searching ?: return default
            val at = findTag(xml, tag, from)
            if (at < 0 || at >= to) return default
            val end = xml.indexOf('>', at)
            val available = attribute(xml, at, end, "available")?.trim().equals("yes", ignoreCase = true)
            val params = attribute(xml, at, end, "supportedParams").orEmpty()
                .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            return NewznabSearchMode(available, params.ifEmpty { if (available) setOf("q") else emptySet() })
        }
        return NewznabCaps(
            search = mode("search", NewznabSearchMode(true, setOf("q"))),
            tvSearch = mode("tv-search", NewznabSearchMode(false, emptySet())),
            movieSearch = mode("movie-search", NewznabSearchMode(false, emptySet())),
            categories = categories,
        )
    }

    private fun item(xml: String, from: Int, to: Int): NewznabItem? {
        val title = element(xml, "title", from, to)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val enclosure = findTag(xml, "enclosure", from).takeIf { it in from until to }
        val enclosureEnd = enclosure?.let { xml.indexOf('>', it) }
        val enclosureUrl = enclosure?.let { attribute(xml, it, enclosureEnd ?: to, "url") }
        val link = (enclosureUrl ?: element(xml, "link", from, to))?.trim()?.takeIf(::isHttp) ?: return null
        val attributes = attributes(xml, from, to)
        val size = attributes["size"]?.toLongOrNull()
            ?: enclosure?.let { attribute(xml, it, enclosureEnd ?: to, "length")?.trim()?.toLongOrNull() }
            ?: element(xml, "size", from, to)?.trim()?.toLongOrNull()
            ?: 0L
        val guid = element(xml, "guid", from, to)?.trim()?.takeIf { it.isNotEmpty() } ?: link
        val published = (element(xml, "pubDate", from, to) ?: attributes["usenetdate"])?.let(::parseDate)
        return NewznabItem(title, guid, link, size.coerceAtLeast(0), published, attributes)
    }

    private fun attributes(xml: String, from: Int, to: Int): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (tag in ATTR_TAGS) {
            var at = findTag(xml, tag, from)
            while (at in from until to) {
                val end = xml.indexOf('>', at)
                if (end < 0) break
                val name = attribute(xml, at, end, "name")?.trim()?.lowercase()
                val value = attribute(xml, at, end, "value")
                if (name != null && value != null) out.putIfAbsent(name, value.trim())
                at = findTag(xml, tag, end)
            }
        }
        return out
    }

    /** `[start, end)` of `<tag …> … </tag>`'s content, or `null`. */
    private fun section(xml: String, tag: String): Pair<Int, Int>? {
        val open = findTag(xml, tag, 0).takeIf { it >= 0 } ?: return null
        val start = xml.indexOf('>', open).takeIf { it >= 0 } ?: return null
        val close = xml.indexOf("</$tag>", start).let { if (it < 0) xml.length else it }
        return start + 1 to close
    }

    private fun element(xml: String, tag: String, from: Int, to: Int): String? {
        val open = findTag(xml, tag, from).takeIf { it in from until to } ?: return null
        val start = xml.indexOf('>', open).takeIf { it in open until to } ?: return null
        if (xml[start - 1] == '/') return ""
        val close = xml.indexOf("</$tag", start).takeIf { it in start..to } ?: return null
        return text(xml.substring(start + 1, close))
    }

    /** Index of `<tag` followed by a space, `>` or `/` at or after [from]; never matches `<tagname-longer`. */
    private fun findTag(xml: String, tag: String, from: Int): Int {
        var at = from
        while (true) {
            at = xml.indexOf("<$tag", at)
            if (at < 0) return -1
            val next = xml.getOrNull(at + tag.length + 1)
            if (next == null || next == '>' || next == '/' || next.isWhitespace()) return at
            at += tag.length + 1
        }
    }

    private fun attribute(xml: String, tagStart: Int, tagEnd: Int, name: String): String? {
        var at = tagStart
        while (true) {
            at = xml.indexOf(name, at)
            if (at < 0 || at >= tagEnd) return null
            val before = xml[at - 1]
            var eq = at + name.length
            while (eq < tagEnd && xml[eq].isWhitespace()) eq++
            if ((before.isWhitespace()) && eq < tagEnd && xml[eq] == '=') {
                var quote = eq + 1
                while (quote < tagEnd && xml[quote].isWhitespace()) quote++
                val mark = xml.getOrNull(quote)
                if (mark == '"' || mark == '\'') {
                    val close = xml.indexOf(mark, quote + 1)
                    if (close in 0..tagEnd) return unescape(xml.substring(quote + 1, close))
                }
                return null
            }
            at += name.length
        }
    }

    private fun text(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("<![CDATA[")) return trimmed.removePrefix("<![CDATA[").substringBefore("]]>")
        return unescape(trimmed)
    }

    internal fun unescape(raw: String): String {
        if ('&' !in raw) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            val semi = if (c == '&') raw.indexOf(';', i).takeIf { it in i + 2..i + MAX_ENTITY } else null
            if (semi == null) {
                out.append(c)
                i++
                continue
            }
            val entity = raw.substring(i + 1, semi)
            val decoded = when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity.startsWith("#x") || entity.startsWith("#X") -> entity.substring(2).toIntOrNull(16)?.let(::codePoint)
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let(::codePoint)
                else -> null
            }
            if (decoded == null) {
                out.append(c)
                i++
            } else {
                out.append(decoded)
                i = semi + 1
            }
        }
        return out.toString()
    }

    private fun codePoint(value: Int): String? =
        if (Character.isValidCodePoint(value) && value != 0) String(Character.toChars(value)) else null

    private fun parseDate(raw: String): Long? = try {
        ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }

    private fun isHttp(url: String) = url.startsWith("https://") || url.startsWith("http://")

    private val ATTR_TAGS = listOf("newznab:attr", "torznab:attr", "attr")
    private const val MAX_ENTITY = 10
}
