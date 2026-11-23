package com.seastreams.tv.domain.streams.format

import com.seastreams.tv.domain.streams.format.TemplateModifiers.isPresent
import com.seastreams.tv.domain.streams.format.TemplateModifiers.jsString
import java.util.Locale

/**
 * Modifiers that take arguments (AIOStreams `compileParameterised`): `replace`, `remove`, `keep`, `join`, `truncate`,
 * `slice`, `default`, `trim`, `translate`, `in` and `time`. Arguments are parsed once, when the template compiles.
 */
internal object TemplateCallModifiers {

    fun compile(source: String, lower: String): CompiledModifier? {
        val open = source.indexOf('(')
        if (open == -1 || !source.endsWith(")")) return null
        val name = lower.substring(0, open)
        val inner = source.substring(open + 1, source.length - 1)
        return when (name) {
            "replace" -> replace(source, inner)
            "remove", "keep" -> removeOrKeep(inner, keeping = name == "keep")
            "join" -> unquote(inner)?.let { raw ->
                val separator = substituteTools(raw)
                return@let { value: Any?, _: ModifierContext -> (value as? List<*>)?.joinToString(separator) { jsString(it) } ?: NotApplicable }
            }
            "truncate" -> inner.trim().toIntOrNull()?.takeIf { it >= 0 }?.let { limit -> { value: Any?, _: ModifierContext -> truncate(value, limit) } }
            "slice" -> slice(inner)
            "default" -> unquote(inner)?.let { fallback -> { value: Any?, _: ModifierContext -> if (isPresent(value)) value else fallback } }
            "trim" -> unquote(inner)?.let { chars -> { value: Any?, _: ModifierContext -> (value as? String)?.trim { it in chars } ?: NotApplicable } }
            "translate" -> quotedArguments(inner).takeIf { it.size >= 2 }?.let { (from, to) ->
                return@let { value: Any?, _: ModifierContext -> (value as? String)?.let { TemplateModifiers.mapChars(it, from, to) } ?: NotApplicable }
            }
            "in" -> contains(inner)
            "time" -> unquote(inner)?.let { pattern ->
                return@let { value: Any?, _: ModifierContext ->
                    numberOf(value)?.let { TemplateValueFormat.durationPattern(TemplateValueFormat.normaliseDuration(it), pattern) } ?: NotApplicable
                }
            }
            else -> null
        }
    }

    private fun replace(source: String, inner: String): CompiledModifier {
        VARIABLE_FORM.matchEntire(inner)?.let { m ->
            val variable = m.groupValues[1]
            val replacement = substituteTools(m.groupValues[3])
            return { value, context ->
                val search = context.resolve(variable)?.let(::jsString)
                if (!replaceable(value)) NotApplicable else if (search.isNullOrEmpty()) value else replaceIn(value, search, replacement)
            }
        }
        val openQuote = source[REPLACE_PREFIX.length]
        val closeQuote = source[source.length - 2]
        val body = source.substring(REPLACE_PREFIX.length + 1, source.length - 2)
        val parts = body.split(Regex(Regex.escape(openQuote.toString()) + "\\s*,\\s*" + Regex.escape(closeQuote.toString())))
        if (parts.size != 2 || parts[0].isEmpty()) return { value, _ -> if (replaceable(value)) value else NotApplicable }
        val search = parts[0]
        val replacement = substituteTools(parts[1])
        val variable = if (search.startsWith("{") && search.endsWith("}")) search.substring(1, search.length - 1) else null
        return { value, context ->
            when {
                !replaceable(value) -> NotApplicable
                variable == null -> replaceIn(value, search, replacement)
                else -> context.resolve(variable)?.let(::jsString)?.takeIf { it.isNotEmpty() }?.let { replaceIn(value, it, replacement) } ?: value
            }
        }
    }

    /** A list matches whole elements, ignoring case; a string replaces every occurrence. */
    private fun replaceIn(value: Any?, search: String, replacement: String): Any? = when (value) {
        is String -> value.replace(search, replacement)
        is List<*> -> value.map { item -> if (jsString(item).equals(search, ignoreCase = true)) replacement else item }
        else -> value
    }

    private fun replaceable(value: Any?): Boolean = value is String || value is List<*>

    private fun removeOrKeep(inner: String, keeping: Boolean): CompiledModifier {
        val targets = quotedArguments(inner).filter { it.isNotEmpty() }
        val references = referenceArguments(inner)
        if (targets.isEmpty() && references.isEmpty()) return { _, _ -> NotApplicable }
        return { value, context ->
            val all = targets + references.flatMap { valuesOf(context.resolve(it)) }
            when (value) {
                is String -> if (keeping) NotApplicable else all.fold(value) { acc, target -> acc.replace(target, "") }
                is List<*> -> {
                    val set = all.map { it.lowercase(Locale.ROOT) }.toSet()
                    value.filter { (jsString(it).lowercase(Locale.ROOT) in set) == keeping }
                }
                else -> NotApplicable
            }
        }
    }

    private fun contains(inner: String): CompiledModifier? {
        val options = quotedArguments(inner).map { it.lowercase(Locale.ROOT) }
        val references = referenceArguments(inner)
        if (options.isEmpty() && references.isEmpty()) return null
        return { value, context ->
            val set = (options + references.flatMap { ref -> valuesOf(context.resolve(ref)).map { it.lowercase(Locale.ROOT) } }).toSet()
            when (value) {
                null -> false
                is List<*> -> value.any { it is String && it.lowercase(Locale.ROOT) in set }
                else -> jsString(value).lowercase(Locale.ROOT) in set
            }
        }
    }

    private fun slice(inner: String): CompiledModifier? {
        val parts = inner.split(',').map { it.trim().toIntOrNull() }
        val start = parts.firstOrNull() ?: return null
        val end = parts.getOrNull(1)
        return { value, _ ->
            (value as? List<*>)?.let { list ->
                val from = jsIndex(start, list.size)
                val to = end?.let { jsIndex(it, list.size) } ?: list.size
                if (from >= to) emptyList() else list.subList(from, to)
            } ?: NotApplicable
        }
    }

    private fun jsIndex(index: Int, size: Int): Int = if (index < 0) maxOf(0, size + index) else minOf(index, size)

    private fun truncate(value: Any?, limit: Int): Any? {
        if (value !is String) return NotApplicable
        val points = value.codePoints().toArray()
        if (points.size <= limit) return value
        return String(points, 0, limit).trimEnd() + "…"
    }

    private fun numberOf(value: Any?): Double? = when (value) {
        is Long -> value.toDouble()
        is Int -> value.toDouble()
        is Double -> value
        else -> null
    }

    private fun valuesOf(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is List<*> -> value.filterIsInstance<String>()
        else -> listOf(jsString(value))
    }

    /** `{tools.newLine}` written inside an argument, e.g. `join('{tools.newLine}- ')`. */
    private fun substituteTools(text: String): String =
        text.replace("{tools.newLine}", NEW_LINE_SENTINEL).replace("{tools.removeLine}", REMOVE_LINE_SENTINEL)

    private fun unquote(argument: String): String? {
        val arg = argument.trim()
        if (arg.length < 2) return null
        val quote = arg.first()
        return if ((quote == '\'' || quote == '"') && arg.last() == quote) arg.substring(1, arg.length - 1) else null
    }

    private fun quotedArguments(inner: String): List<String> =
        QUOTED.findAll(inner).map { it.groups[1]?.value ?: it.groups[2]?.value.orEmpty() }.toList()

    private fun referenceArguments(inner: String): List<String> =
        REFERENCE.findAll(inner.replace(QUOTED, "")).map { it.groupValues[1] }.distinct().toList()

    private const val REPLACE_PREFIX = "replace("
    private val VARIABLE_FORM = Regex("^\\s*\\{([^}]+)\\}\\s*,\\s*(['\"])([\\s\\S]*)\\2\\s*$")
    private val QUOTED = Regex("\"([^\"]*)\"|'([^']*)'")
    private val REFERENCE = Regex("\\{\\s*([A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*)\\s*\\}")
}

/** Layout directives travel through a render as control characters, so only the template can emit them. */
internal const val NEW_LINE_SENTINEL = "\u0011"
internal const val REMOVE_LINE_SENTINEL = "\u0012"
