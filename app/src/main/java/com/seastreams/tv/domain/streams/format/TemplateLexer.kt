package com.seastreams.tv.domain.streams.format

/*
 * Template lexing: the character-level scanning the parser builds on (AIOStreams `engine/parser.ts` scanners).
 */

/** A cursor over template text (AIOStreams `Scanner`). */
internal class Scanner(val input: String, var pos: Int = 0) {
    val atEnd: Boolean get() = pos >= input.length

    fun peek(offset: Int = 0): Char? = input.getOrNull(pos + offset)

    fun startsWith(text: String): Boolean = input.startsWith(text, pos)

    fun eat(text: String): Boolean {
        if (!startsWith(text)) return false
        pos += text.length
        return true
    }

    fun slice(from: Int): String = input.substring(from, pos)

    fun skipSpaces() {
        while (peek()?.isWhitespace() == true) pos++
    }
}

/** How a call modifier's argument list is shaped; decides whether an expression parses at all. */
internal enum class ArgumentShape { QUOTED, QUOTED_PAIR, REPLACE_ARGS, DIGITS, DIGITS_OR_PAIR, LOOSE }

/** Call modifiers SeaStreams supports, with their argument shapes (AIOStreams `CALL_MODIFIERS`). */
internal val CALL_MODIFIERS: List<Pair<String, ArgumentShape>> = listOf(
    "replace" to ArgumentShape.REPLACE_ARGS,
    "remove" to ArgumentShape.LOOSE,
    "keep" to ArgumentShape.LOOSE,
    "join" to ArgumentShape.QUOTED,
    "truncate" to ArgumentShape.DIGITS,
    "slice" to ArgumentShape.DIGITS_OR_PAIR,
    "time" to ArgumentShape.QUOTED,
    "default" to ArgumentShape.QUOTED,
    "in" to ArgumentShape.LOOSE,
    "translate" to ArgumentShape.QUOTED_PAIR,
    "trim" to ArgumentShape.QUOTED,
)

/** Scans one call's `(…)` argument list; leaves [Scanner.pos] after `)` and returns `true` when well formed. */
internal fun scanCallArguments(scanner: Scanner, shape: ArgumentShape): Boolean {
    if (!scanner.eat("(")) return false
    when (shape) {
        ArgumentShape.QUOTED -> if (!scanQuoted(scanner)) return false
        ArgumentShape.QUOTED_PAIR -> {
            if (!scanQuoted(scanner)) return false
            scanner.skipSpaces()
            if (!scanner.eat(",")) return false
            scanner.skipSpaces()
            if (!scanQuoted(scanner)) return false
        }
        ArgumentShape.REPLACE_ARGS -> {
            if (scanner.peek() == '{') {
                while (!scanner.atEnd && scanner.peek() != '}') scanner.pos++
                if (!scanner.eat("}")) return false
            } else if (!scanQuoted(scanner)) {
                return false
            }
            scanner.skipSpaces()
            if (!scanner.eat(",")) return false
            scanner.skipSpaces()
            if (!scanQuoted(scanner)) return false
        }
        ArgumentShape.DIGITS -> if (!scanDigits(scanner)) return false
        ArgumentShape.DIGITS_OR_PAIR -> {
            scanner.skipSpaces()
            if (!scanDigits(scanner)) return false
            scanner.skipSpaces()
            if (scanner.eat(",")) {
                scanner.skipSpaces()
                if (!scanDigits(scanner)) return false
                scanner.skipSpaces()
            }
        }
        ArgumentShape.LOOSE -> return scanLoose(scanner) && scanner.eat(")")
    }
    return scanner.eat(")")
}

/** A quote closes only before `,`, `)`, whitespace or the end, so `replace("Director's Cut", 'x')` keeps its apostrophe. */
private fun scanQuoted(scanner: Scanner): Boolean {
    val quote = scanner.peek()
    if (quote != '\'' && quote != '"') return false
    scanner.pos++
    while (!scanner.atEnd) {
        if (scanner.peek() == quote) {
            val after = scanner.peek(1)
            if (after == null || after == ',' || after == ')' || after.isWhitespace()) {
                scanner.pos++
                return true
            }
        }
        scanner.pos++
    }
    return false
}

private fun scanDigits(scanner: Scanner): Boolean {
    val start = scanner.pos
    while (scanner.peek()?.isDigit() == true) scanner.pos++
    return scanner.pos > start
}

/** Up to the last `)` before `}`, `[`, `]` or `::`, skipping quoted text and `{section.property}` references. */
private fun scanLoose(scanner: Scanner): Boolean {
    var lastParen = -1
    while (!scanner.atEnd) {
        val char = scanner.peek() ?: break
        if (char == '\'' || char == '"') {
            val close = scanner.input.indexOf(char, scanner.pos + 1)
            if (close != -1) {
                scanner.pos = close + 1
                continue
            }
        }
        if (char == '{') {
            val close = scanner.input.indexOf('}', scanner.pos)
            val span = if (close == -1) "" else scanner.input.substring(scanner.pos, close + 1)
            if (!LOOSE_REFERENCE.matches(span)) break
            scanner.pos = close + 1
            continue
        }
        if (char == '}' || char == '[' || char == ']') break
        if (char == ':' && scanner.peek(1) == ':') break
        if (char == ')') lastParen = scanner.pos
        scanner.pos++
    }
    if (lastParen == -1) return false
    scanner.pos = lastParen
    return true
}

private val LOOSE_REFERENCE = Regex("^\\{\\s*[A-Za-z_][A-Za-z0-9_]*\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*\\s*\\}$")
