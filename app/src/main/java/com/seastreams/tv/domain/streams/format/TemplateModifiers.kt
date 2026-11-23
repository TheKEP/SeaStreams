package com.seastreams.tv.domain.streams.format

import com.seastreams.tv.domain.streams.format.TemplateValueFormat.BINARY
import com.seastreams.tv.domain.streams.format.TemplateValueFormat.DECIMAL
import com.seastreams.tv.domain.streams.parse.LanguageTable
import java.text.NumberFormat
import java.util.Base64
import java.util.Locale
import kotlin.random.Random

/** Returned by a modifier that doesn't apply to the value's type (the renderer reports `{unknown_…_modifier(…)}`). */
internal object NotApplicable

/** Looks up `{section.property}` references used inside modifier arguments. */
internal fun interface ModifierContext {
    fun resolve(source: String): Any?
}

internal typealias CompiledModifier = (value: Any?, context: ModifierContext) -> Any?

/**
 * Formatter modifiers (AIOStreams `engine/modifiers.ts`). Values are `String`, `Long` / `Double`, `Boolean`,
 * `List<String>` or `null`; a modifier returns [NotApplicable] for a type it doesn't handle.
 */
internal object TemplateModifiers {

    private val stringModifiers: Map<String, (String) -> Any?> = mapOf(
        "upper" to { v -> v.uppercase(Locale.ROOT) },
        "lower" to { v -> v.lowercase(Locale.ROOT) },
        "trim" to { v -> v.trim() },
        "title" to { v -> v.split(" ").joinToString(" ") { w -> w.lowercase(Locale.ROOT).replaceFirstChar { it.uppercaseChar() } } },
        "length" to { v -> v.length.toString() },
        "reverse" to { v -> v.reversed() },
        "base64" to { v -> Base64.getEncoder().encodeToString(v.toByteArray()) },
        "string" to { v -> v },
        "smallcaps" to { v -> TemplateValueFormat.smallCaps(v) },
        "subscript" to { v -> mapChars(v, DIGITS, SUBSCRIPT) },
        "superscript" to { v -> mapChars(v, DIGITS, SUPERSCRIPT) },
        "languagecode" to ::languageCode,
        "languageemoji" to ::languageEmoji,
    )

    private val listModifiers: Map<String, (List<String>) -> Any?> = mapOf(
        "join" to { v -> v.joinToString(", ") },
        "length" to { v -> v.size.toString() },
        "first" to { v -> v.firstOrNull().orEmpty() },
        "last" to { v -> v.lastOrNull().orEmpty() },
        "random" to { v -> if (v.isEmpty()) "" else v[Random.nextInt(v.size)] },
        "sort" to { v -> v.sortedWith(NaturalOrder) },
        "rsort" to { v -> v.sortedWith(NaturalOrder.reversed()) },
        "lsort" to { v -> v.sorted() },
        "reverse" to { v -> v.reversed() },
        "unique" to { v -> v.distinctBy { it.lowercase(Locale.ROOT) } },
        "languagecode" to { v -> v.map(::languageCode).filter { it.isNotEmpty() }.distinct() },
        "languageemoji" to { v -> v.map(::languageEmoji).filter { it.isNotEmpty() }.distinct() },
        "string" to { v -> v.joinToString(",") },
    )

    private val numberModifiers: Map<String, (Double) -> Any?> = mapOf(
        "comma" to { v -> NumberFormat.getNumberInstance(Locale.US).format(v) },
        "hex" to { v -> v.toLong().toString(16) },
        "octal" to { v -> v.toLong().toString(8) },
        "binary" to { v -> v.toLong().toString(2) },
        "bytes" to { v -> TemplateValueFormat.bytes(v, DECIMAL) },
        "sbytes" to { v -> TemplateValueFormat.smartBytes(v, DECIMAL) },
        "sbytes10" to { v -> TemplateValueFormat.smartBytes(v, DECIMAL) },
        "sbytes2" to { v -> TemplateValueFormat.smartBytes(v, BINARY) },
        "rbytes" to { v -> TemplateValueFormat.bytes(v, DECIMAL, round = true) },
        "bytes10" to { v -> TemplateValueFormat.bytes(v, DECIMAL) },
        "rbytes10" to { v -> TemplateValueFormat.bytes(v, DECIMAL, round = true) },
        "bytes2" to { v -> TemplateValueFormat.bytes(v, BINARY) },
        "rbytes2" to { v -> TemplateValueFormat.bytes(v, BINARY, round = true) },
        "bitrate" to { v -> TemplateValueFormat.bitrate(v) },
        "rbitrate" to { v -> TemplateValueFormat.bitrate(v, round = true) },
        "sbitrate" to { v -> TemplateValueFormat.smartBitrate(v) },
        "string" to { v -> TemplateValueFormat.jsNumber(v) },
        "time" to { v -> TemplateValueFormat.duration(TemplateValueFormat.normaliseDuration(v)) },
        "star" to { v -> stars(v, pad = false) },
        "pstar" to { v -> stars(v, pad = true) },
    )

    private val exactConditionals: Map<String, (Any?) -> Boolean> = mapOf(
        "istrue" to { v -> v == true },
        "isfalse" to { v -> v == false },
        "exists" to ::isPresent,
    )

    /** Longest first, so `>=` wins over `>`. */
    val PREFIX_OPERATORS: List<String> = listOf(">=", "<=", "$", "^", "~", "=", ">", "<")

    /** Every plain modifier name, longest first so `sbytes10` wins over `sbytes`. */
    val PLAIN_NAMES: List<String> =
        (stringModifiers.keys + listModifiers.keys + numberModifiers.keys + exactConditionals.keys).distinct().sortedByDescending { it.length }

    /** Conditionals first, since `::exists` and `::>5` apply to every type. */
    fun compile(source: String): CompiledModifier {
        val lower = source.lowercase(Locale.ROOT)
        return conditional(lower) ?: TemplateCallModifiers.compile(source, lower) ?: plain(lower)
    }

    private fun conditional(lower: String): CompiledModifier? {
        exactConditionals[lower]?.let { check -> return { value, _ -> isPresent(value) && check(value) } }
        val operator = PREFIX_OPERATORS.firstOrNull { lower.startsWith(it) } ?: return null
        val rawCheck = lower.substring(operator.length)
        return { value, _ -> compare(operator, rawCheck, value) }
    }

    private fun compare(operator: String, rawCheck: String, value: Any?): Any {
        if (!isPresent(value)) return false
        val stringList = (value as? List<*>)?.takeIf { list -> list.all { it is String } }?.map { it.toString().lowercase(Locale.ROOT) }
        val stringValue = jsString(value).lowercase(Locale.ROOT)
        val check = if (stringValue.any { it.isWhitespace() }) rawCheck else rawCheck.filterNot { it.isWhitespace() }
        val numericValue = jsToNumber(stringValue.replace(COMMA_SPACE, ""))
        val numericCheck = jsToNumber(check.replace(COMMA_SPACE, ""))
        if (operator in NUMERIC_OPERATORS && numericValue != null && numericCheck != null) {
            return when (operator) {
                "=" -> numericValue == numericCheck
                ">=" -> numericValue >= numericCheck
                ">" -> numericValue > numericCheck
                "<=" -> numericValue <= numericCheck
                else -> numericValue < numericCheck
            }
        }
        return when (operator) {
            "$" -> stringList?.firstOrNull()?.equals(check) ?: stringValue.startsWith(check)
            "^" -> stringList?.lastOrNull()?.equals(check) ?: stringValue.endsWith(check)
            "~" -> stringList?.contains(check) ?: stringValue.contains(check)
            "=" -> stringValue == check
            ">=" -> stringValue >= check
            ">" -> stringValue > check
            "<=" -> stringValue <= check
            else -> stringValue < check
        }
    }

    private fun plain(lower: String): CompiledModifier = { value, _ ->
        when (value) {
            is String -> stringModifiers[lower]?.invoke(value) ?: NotApplicable
            is List<*> -> listModifiers[lower]?.invoke(value.map { jsString(it) }) ?: NotApplicable
            is Long -> numberModifiers[lower]?.invoke(value.toDouble()) ?: NotApplicable
            is Int -> numberModifiers[lower]?.invoke(value.toDouble()) ?: NotApplicable
            is Double -> numberModifiers[lower]?.invoke(value) ?: NotApplicable
            is Boolean -> if (lower == "string") value.toString() else NotApplicable
            else -> NotApplicable
        }
    }

    /** Same notion of presence as `::exists`: not null, not blank, not an empty list. */
    fun isPresent(value: Any?): Boolean = when (value) {
        null -> false
        is String -> value.isNotBlank()
        is List<*> -> value.isNotEmpty()
        else -> true
    }

    /** JavaScript `String(value)`: lists join with `,`, numbers print without a trailing `.0`. */
    fun jsString(value: Any?): String = when (value) {
        null -> ""
        is Double -> TemplateValueFormat.jsNumber(value)
        is List<*> -> value.joinToString(",") { jsString(it) }
        else -> value.toString()
    }

    /** JavaScript `Number(text)` for comparisons: blank is `0`, anything unparseable is `null` (NaN). */
    private fun jsToNumber(text: String): Double? = if (text.isBlank()) 0.0 else text.trim().toDoubleOrNull()

    fun mapChars(value: String, from: String, to: String): String {
        val table = from.zip(to).toMap()
        return value.map { table[it] ?: it }.joinToString("")
    }

    private fun languageCode(name: String): String = LanguageTable.code(name) ?: name.uppercase(Locale.ROOT)

    private fun languageEmoji(name: String): String = LanguageTable.emoji(name).orEmpty()

    private fun stars(value: Double, pad: Boolean): String {
        val full = (value / STAR_STEP).toInt()
        val half = if (value % STAR_STEP >= STAR_STEP / 2) 1 else 0
        return "★".repeat(full) + "⯪".repeat(half) + if (pad) "☆".repeat(maxOf(0, STARS - full - half)) else ""
    }

    /** `localeCompare(…, { numeric: true })`: digit runs compare by value. */
    private object NaturalOrder : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            val x = CHUNK.findAll(a).map { it.value }.toList()
            val y = CHUNK.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(x.size, y.size)) {
                val nx = x[i].toBigIntegerOrNull()
                val ny = y[i].toBigIntegerOrNull()
                val cmp = if (nx != null && ny != null) nx.compareTo(ny) else x[i].compareTo(y[i], ignoreCase = true)
                if (cmp != 0) return cmp
            }
            return x.size - y.size
        }
    }

    private val CHUNK = Regex("[0-9]+|[^0-9]+")
    private val COMMA_SPACE = Regex(",\\s")
    private val NUMERIC_OPERATORS = setOf("<", "<=", ">", ">=", "=")
    private const val DIGITS = "0123456789+-=()"
    private const val SUBSCRIPT = "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎"
    private const val SUPERSCRIPT = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾"
    private const val STAR_STEP = 20.0
    private const val STARS = 5
}
