package com.seastreams.tv.core.webserver

/**
 * A small JavaScript lexer for tests: no JS engine ships with the JVM (Nashorn is gone) and adding one isn't approved,
 * so this catches the mistakes that blank the portal page. Those are a string closed early by a stray quote (`'profile's'`),
 * an unterminated string, template, comment or regex literal, and unbalanced `()[]{}`. It also catches `${…}` placeholders,
 * which in the portal can only come from Kotlin raw-string interpolation going wrong. It is not a full parser, so code
 * that tokenises cleanly can still fail at runtime.
 */
internal object JsSyntaxChecker {

    /** Every problem found, as `line N: message`; empty when the script tokenises cleanly. */
    fun check(source: String): List<String> {
        val errors = mutableListOf<String>()
        val brackets = ArrayDeque<Pair<Char, Int>>()
        var i = 0
        var line = 1
        // The last significant (non-space, non-comment) token's final char, to tell a regex `/` from division.
        var previous: Char? = null
        var previousWord = ""

        fun error(at: Int, message: String) {
            val text = source.lines().getOrElse(at - 1) { "" }.trim().take(EXCERPT_CHARS)
            errors += "line $at: $message — $text"
        }

        while (i < source.length) {
            val c = source[i]
            when {
                c == '\n' -> { line++; i++ }
                c.isWhitespace() -> i++
                source.startsWith("//", i) -> while (i < source.length && source[i] != '\n') i++
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2)
                    if (end == -1) { error(line, "unterminated block comment"); return errors }
                    line += source.substring(i, end).count { it == '\n' }
                    i = end + 2
                }
                c == '\'' || c == '"' -> {
                    val start = line
                    i++
                    var closed = false
                    while (i < source.length) {
                        val d = source[i]
                        if (d == '\\') { i += 2; continue }
                        if (d == '\n') break
                        i++
                        if (d == c) { closed = true; break }
                    }
                    if (!closed) error(start, "unterminated $c string")
                    previous = c
                    previousWord = ""
                }
                c == '`' -> {
                    val start = line
                    i++
                    var closed = false
                    while (i < source.length) {
                        val d = source[i]
                        if (d == '\\') { i += 2; continue }
                        if (d == '\n') line++
                        if (d == '$' && source.getOrNull(i + 1) == '{') error(line, "\${…} in a template literal (Kotlin interpolation risk)")
                        i++
                        if (d == '`') { closed = true; break }
                    }
                    if (!closed) { error(start, "unterminated template literal"); return errors }
                    previous = '`'
                    previousWord = ""
                }
                c == '/' && startsRegex(previous, previousWord) -> {
                    val start = line
                    i++
                    var inClass = false
                    var closed = false
                    while (i < source.length && source[i] != '\n') {
                        val d = source[i]
                        if (d == '\\') { i += 2; continue }
                        i++
                        if (d == '[') inClass = true else if (d == ']') inClass = false else if (d == '/' && !inClass) { closed = true; break }
                    }
                    if (!closed) error(start, "unterminated regex literal")
                    while (i < source.length && source[i].isLetter()) i++
                    previous = '/'
                    previousWord = ""
                }
                c == '(' || c == '[' || c == '{' -> { brackets.addLast(c to line); previous = c; previousWord = ""; i++ }
                c == ')' || c == ']' || c == '}' -> {
                    val open = brackets.removeLastOrNull()
                    if (open == null || PAIRS[open.first] != c) {
                        error(line, if (open == null) "unexpected '$c'" else "'$c' closes '${open.first}' opened on line ${open.second}")
                        return errors
                    }
                    previous = c
                    previousWord = ""
                    i++
                }
                c.isJavaIdentifierPart() -> {
                    val start = i
                    while (i < source.length && source[i].isJavaIdentifierPart()) i++
                    previousWord = source.substring(start, i)
                    previous = source[i - 1]
                }
                else -> { previous = c; previousWord = ""; i++ }
            }
        }
        brackets.forEach { (open, at) -> error(at, "'$open' is never closed") }
        return errors
    }

    /** A `/` starts a regex after an operator or opening bracket, or after `return` / `typeof`; else it divides. */
    private fun startsRegex(previous: Char?, previousWord: String): Boolean = when {
        previous == null -> true
        previousWord in REGEX_KEYWORDS -> true
        previousWord.isNotEmpty() -> false
        else -> previous in "(,=:[!&|?{};+-*%<>~^"
    }

    private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}')
    private val REGEX_KEYWORDS = setOf("return", "typeof", "case", "in", "of", "delete", "void", "throw", "new")
    private const val EXCERPT_CHARS = 100
}
