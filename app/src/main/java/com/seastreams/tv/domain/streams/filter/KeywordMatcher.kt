package com.seastreams.tv.domain.streams.filter

import com.seastreams.tv.domain.streams.parse.ParsedStream

/**
 * A compiled user pattern. AIOStreams writes regexes as `/pattern/flags`; besides the JavaScript flags it reads an
 * extra `n` flag that *negates* the pattern: a negated pattern matches a stream when it matches none of its texts.
 */
class StreamPattern internal constructor(val source: String, private val regex: Regex, val negate: Boolean) {

    /** Whether the stream's file name, release group or indexer matches (or, negated, none does). */
    fun matches(stream: ParsedStream): Boolean {
        val hit = stream.matchTexts.any { regex.containsMatchIn(it) }
        return if (negate) !hit else hit
    }

    fun matches(text: String): Boolean = regex.containsMatchIn(text) != negate
}

/** Compiles keyword lists and user regexes the way AIOStreams does (`buildKeywordRegexPattern`, `parseRegex`). */
object KeywordMatcher {

    /**
     * `/pattern/flags` or a bare pattern; `null` when it is blank or not a valid regex (a bad pattern from the portal
     * must never break stream resolution). Flags: `i` ignore case, `m` multiline, `s` dot-all, `n` negate; `g`, `u`, `y`
     * mean nothing for a yes/no test and are ignored.
     */
    fun compile(raw: String): StreamPattern? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val slashed = SLASHED.matchEntire(text)
        val body = slashed?.groupValues?.get(1) ?: text
        val flags = slashed?.groupValues?.get(2).orEmpty()
        val options = buildSet {
            if ('i' in flags) add(RegexOption.IGNORE_CASE)
            if ('m' in flags) add(RegexOption.MULTILINE)
            if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
        }
        return try {
            StreamPattern(text, Regex(body, options), negate = 'n' in flags)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Valid patterns among [raw], in order. */
    fun compileAll(raw: List<String>): List<StreamPattern> = raw.mapNotNull(::compile)

    /**
     * One case-insensitive pattern matching any of [keywords] as a whole token (a space inside a keyword also
     * matches `.`, `-` or `_`), or `null` for an empty list.
     */
    fun keywords(keywords: List<String>): StreamPattern? {
        val words = keywords.map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val alternatives = words.joinToString("|") { word ->
            word.split(WHITESPACE).joinToString("[\\s.\\-_]?") { Regex.escape(it) }
        }
        val pattern = "(?:^|(?<![^ \\[(_\\-.]))($alternatives)(?=[ \\[(\\)\\]_.-]|$)"
        return StreamPattern(words.joinToString(", "), Regex(pattern, RegexOption.IGNORE_CASE), negate = false)
    }

    /** `true` when a valid `/pattern/flags` (or bare) regex; the portal validates input with this. */
    fun isValid(raw: String): Boolean = compile(raw) != null

    private val SLASHED = Regex("^/(.+)/([gimsuyn]*)$", RegexOption.DOT_MATCHES_ALL)
    private val WHITESPACE = Regex("\\s+")
}
