package com.seastreams.tv.domain.streams.format

/**
 * Parses AIOStreams template syntax (`formatters/engine/parser.ts`) into [TemplateNode]s. It never throws: a `{…}` that
 * isn't a valid expression stays literal text, or becomes `{invalid_expression(…)}` when it looks like a field
 * reference, so the mistake is visible in the preview.
 */
object TemplateParser {

    fun parse(template: String): List<TemplateNode> {
        val scanner = Scanner(template)
        val nodes = mutableListOf<TemplateNode>()
        var literalStart = 0
        fun flush(end: Int) {
            if (end > literalStart) nodes += TemplateNode.Raw(template.substring(literalStart, end))
        }
        while (!scanner.atEnd) {
            if (scanner.peek() != '{') {
                scanner.pos++
                continue
            }
            val brace = scanner.pos
            if (scanner.startsWith("{?")) {
                val body = groupBody(scanner)
                if (body != null) {
                    flush(brace)
                    nodes += TemplateNode.Group(parse(body))
                    literalStart = scanner.pos
                    continue
                }
            }
            val node = tool(scanner) ?: expression(scanner)
            if (node == null) {
                val closing = template.indexOf('}', brace)
                val inner = if (closing == -1) "" else template.substring(brace + 1, closing)
                if (!inner.contains('{') && LOOKS_LIKE_EXPRESSION.containsMatchIn(inner)) {
                    flush(brace)
                    nodes += TemplateNode.Raw("{invalid_expression(${inner.trim()})}")
                    scanner.pos = closing + 1
                    literalStart = scanner.pos
                } else {
                    scanner.pos = brace + 1
                }
                continue
            }
            flush(brace)
            nodes += node
            literalStart = scanner.pos
        }
        flush(template.length)
        return nodes
    }

    /** The text between `{?` and its matching `?}` (groups nest), or `null` when unterminated. */
    private fun groupBody(scanner: Scanner): String? {
        val start = scanner.pos
        scanner.pos += 2
        val from = scanner.pos
        var depth = 1
        while (!scanner.atEnd) {
            when {
                scanner.startsWith("{?") -> {
                    depth++
                    scanner.pos += 2
                }
                scanner.startsWith("?}") -> {
                    depth--
                    if (depth == 0) {
                        val body = scanner.slice(from)
                        scanner.pos += 2
                        return body
                    }
                    scanner.pos += 2
                }
                else -> scanner.pos++
            }
        }
        scanner.pos = start
        return null
    }

    private fun tool(scanner: Scanner): TemplateNode.Tool? = when {
        scanner.eat("{tools.newLine}") -> TemplateNode.Tool(ToolKind.NEW_LINE)
        scanner.eat("{tools.removeLine}") -> TemplateNode.Tool(ToolKind.REMOVE_LINE)
        else -> null
    }

    private fun expression(scanner: Scanner): TemplateNode.Expression? {
        val start = scanner.pos
        fun fail(): TemplateNode.Expression? {
            scanner.pos = start
            return null
        }
        if (!scanner.eat("{")) return fail()
        scanner.skipSpaces()
        val operands = mutableListOf(operand(scanner) ?: return fail())
        val comparators = mutableListOf<String>()
        while (scanner.startsWith("::")) {
            val save = scanner.pos
            scanner.pos += 2
            val comparator = COMPARATORS.firstOrNull { scanner.startsWith("$it::") }
            if (comparator == null) {
                scanner.pos = save
                break
            }
            scanner.pos += comparator.length + 2
            operands += operand(scanner) ?: return fail()
            comparators += comparator
        }
        val check = if (scanner.peek() == '[') check(scanner) else null
        scanner.skipSpaces()
        if (!scanner.eat("}")) return fail()
        return TemplateNode.Expression(scanner.slice(start), operands, comparators, check)
    }

    private fun operand(scanner: Scanner): Operand? {
        val head: Operand = if (scanner.peek() == '\'' || scanner.peek() == '"') {
            val quote = scanner.peek()
            val start = scanner.pos
            scanner.pos++
            val from = scanner.pos
            while (!scanner.atEnd && scanner.peek() != quote) scanner.pos++
            if (scanner.atEnd) {
                scanner.pos = start
                return null
            }
            val literal = scanner.slice(from)
            scanner.pos++
            Operand("", "", emptyList(), literal)
        } else {
            operandHead(scanner) ?: return null
        }
        val modifiers = mutableListOf<String>()
        while (scanner.startsWith("::")) {
            val save = scanner.pos
            scanner.pos += 2
            if (COMPARATORS.any { scanner.startsWith("$it::") }) {
                scanner.pos = save
                break
            }
            val modifier = modifier(scanner)
            if (modifier == null) {
                scanner.pos = save
                break
            }
            modifiers += modifier
        }
        return head.copy(modifiers = modifiers)
    }

    /** `section.property`, matched case-insensitively against [TemplateFields.canonical]. */
    private fun operandHead(scanner: Scanner): Operand? {
        val start = scanner.pos
        while (scanner.peek()?.isIdentifier() == true) scanner.pos++
        val section = scanner.slice(start)
        if (section.isEmpty() || scanner.peek() != '.') {
            scanner.pos = start
            return null
        }
        scanner.pos++
        val propertyStart = scanner.pos
        while (scanner.peek()?.isIdentifier() == true) scanner.pos++
        val property = scanner.slice(propertyStart)
        val canonical = TemplateFields.canonical(section, property)
        if (property.isEmpty() || canonical == null) {
            scanner.pos = start
            return null
        }
        return Operand(canonical.first, canonical.second, emptyList())
    }

    /** One `::modifier`: a call, a prefix comparison (`>=5`, `=2160p`, `~HDR`) or a plain name. */
    private fun modifier(scanner: Scanner): String? {
        val start = scanner.pos
        for ((name, shape) in CALL_MODIFIERS) {
            if (!scanner.startsWith("$name(")) continue
            scanner.pos += name.length
            if (scanCallArguments(scanner, shape)) return scanner.slice(start)
            scanner.pos = start
            break
        }
        for (operator in TemplateModifiers.PREFIX_OPERATORS) {
            if (scanner.eat(operator)) {
                while (!scanner.atEnd) {
                    val char = scanner.peek()
                    if (char == '}' || char == '[' || char == ']' || (char == ':' && scanner.peek(1) == ':')) break
                    scanner.pos++
                }
                return scanner.slice(start)
            }
        }
        for (name in TemplateModifiers.PLAIN_NAMES) {
            if (!scanner.startsWith(name) || scanner.peek(name.length)?.isIdentifier() == true) continue
            scanner.pos += name.length
            return scanner.slice(start)
        }
        scanner.pos = start
        return null
    }

    /** `["true"||"false"]` with an optional `||"absent"`; a `\"` inside a branch is a literal quote. */
    private fun check(scanner: Scanner): Check? {
        val start = scanner.pos
        fun fail(): Check? {
            scanner.pos = start
            return null
        }
        if (!scanner.eat("[")) return fail()
        val whenTrue = branch(scanner) ?: return fail()
        if (!scanner.eat("||")) return fail()
        val whenFalse = branch(scanner) ?: return fail()
        var whenAbsent: String? = null
        if (scanner.eat("||")) whenAbsent = branch(scanner) ?: return fail()
        if (!scanner.eat("]")) return fail()
        return Check(whenTrue, whenFalse, whenAbsent)
    }

    /** Brace depth is tracked so a quote inside a nested `{…}` doesn't close the branch (conditionals nest). */
    private fun branch(scanner: Scanner): String? {
        if (!scanner.eat("\"")) return null
        val text = StringBuilder()
        var depth = 0
        while (!scanner.atEnd) {
            val char = scanner.peek() ?: break
            if (char == '\\' && scanner.peek(1) == '"') {
                text.append('"')
                scanner.pos += 2
                continue
            }
            when {
                char == '{' -> depth++
                char == '}' -> depth = maxOf(0, depth - 1)
                char == '"' && depth == 0 -> {
                    scanner.pos++
                    return text.toString()
                }
            }
            text.append(char)
            scanner.pos++
        }
        return null
    }

    private fun Char.isIdentifier(): Boolean = this == '_' || this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    internal val COMPARATORS = listOf("and", "or", "xor", "neq", "equal", "left", "right")
    private val LOOKS_LIKE_EXPRESSION = Regex("^\\s*[A-Za-z_][A-Za-z0-9_]*\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*")
}
