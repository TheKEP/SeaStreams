package com.seastreams.tv.domain.streams.format

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/**
 * Number and text formatting behind the formatter modifiers, ported from AIOStreams `formatters/utils.ts` with
 * JavaScript's number printing (`63`, not `63.0`) and `toFixed` / `Math.round` rounding, so output matches AIOStreams.
 */
internal object TemplateValueFormat {

    private val DECIMAL_UNITS = listOf("B", "KB", "MB", "GB", "TB")
    private val BINARY_UNITS = listOf("B", "KiB", "MiB", "GiB", "TiB")
    private val BITRATE_UNITS = listOf("bps", "Kbps", "Mbps", "Gbps", "Tbps")

    /** `formatBytes`: two decimals (or whole with [round]), e.g. `63 GB`, `1.37 GiB`. */
    fun bytes(bytes: Double, k: Int, round: Boolean = false): String {
        if (bytes == 0.0) return "0 B"
        val units = if (k == BINARY) BINARY_UNITS else DECIMAL_UNITS
        val i = unitIndex(bytes, k, units.size)
        var value = toFixed(bytes / k.toDouble().pow(i), 2)
        if (round) value = jsRound(value)
        return jsNumber(value) + " " + units[i]
    }

    /** `formatSmartBytes`: 3 significant-ish digits (`123 GB`, `12.5 GB`, `1.25 GB`). */
    fun smartBytes(bytes: Double, k: Int): String {
        if (bytes == 0.0) return "0 B"
        val units = if (k == BINARY) BINARY_UNITS else DECIMAL_UNITS
        val i = unitIndex(bytes, k, units.size)
        return smart(bytes / k.toDouble().pow(i)) + " " + units[i]
    }

    fun bitrate(bitrate: Double, round: Boolean = false): String {
        if (!bitrate.isFinite() || bitrate <= 0) return "0 bps"
        val i = unitIndex(bitrate, DECIMAL, BITRATE_UNITS.size)
        val raw = bitrate / DECIMAL.toDouble().pow(i)
        val value = if (round) jsRound(raw) else toFixed(raw, 2)
        return jsNumber(value) + " " + BITRATE_UNITS[i]
    }

    fun smartBitrate(bitrate: Double): String {
        if (!bitrate.isFinite() || bitrate <= 0) return "0 bps"
        val i = unitIndex(bitrate, DECIMAL, BITRATE_UNITS.size)
        return smart(bitrate / DECIMAL.toDouble().pow(i)) + " " + BITRATE_UNITS[i]
    }

    private fun smart(raw: Double): String {
        val integer = floor(raw)
        return when {
            integer >= 100 -> jsNumber(jsRound(raw))
            integer >= 10 -> {
                val value = toFixed(raw, 1)
                if (value % 1.0 == 0.0) jsNumber(value) else BigDecimal(value).setScale(1, RoundingMode.HALF_UP).toPlainString()
            }
            else -> jsNumber(toFixed(raw, 2))
        }
    }

    private fun unitIndex(value: Double, k: Int, count: Int): Int =
        floor(ln(value) / ln(k.toDouble())).toInt().coerceIn(0, count - 1)

    /** `formatDuration`: `2h:32m:0s`, `5m:3s` or `45m`. */
    fun duration(ms: Double): String {
        val seconds = floor(ms / MILLIS).toLong()
        val minutes = seconds / SIXTY
        val hours = minutes / SIXTY
        val s = seconds % SIXTY
        val m = minutes % SIXTY
        return when {
            hours > 0 -> "${hours}h:${m}m:${s}s"
            s > 0 -> "${m}m:${s}s"
            else -> "${m}m"
        }
    }

    /** `normaliseDuration`: values under 1000 are minutes (TMDB runtimes), larger ones milliseconds. */
    fun normaliseDuration(value: Double): Double = when {
        value < 0 -> 0.0
        value < MILLIS -> value * SIXTY * MILLIS
        else -> value
    }

    /**
     * `formatDurationPattern`: `%H %M %S` zero-padded, `%-H %-M %-S` bare; `[…]` is dropped when every unit in it is
     * zero; the largest unit used carries the overflow (`%-Mm` alone reads `83m`).
     */
    fun durationPattern(ms: Double, pattern: String): String {
        val units = Regex("%-?([HMS])").findAll(pattern).map { it.groupValues[1] }.toSet()
        val totalSeconds = maxOf(0L, floor(ms / MILLIS).toLong())
        val totalMinutes = totalSeconds / SIXTY
        val values = mapOf(
            "H" to totalSeconds / (SIXTY * SIXTY),
            "M" to if ("H" in units) totalMinutes % SIXTY else totalMinutes,
            "S" to if ("H" in units || "M" in units) totalSeconds % SIXTY else totalSeconds,
        )
        return renderPattern(pattern) { token ->
            val padded = !token.startsWith("-")
            val value = values[token.removePrefix("-")] ?: return@renderPattern null
            (if (padded) value.toString().padStart(2, '0') else value.toString()) to (value == 0L)
        }
    }

    private class Frame(val text: StringBuilder = StringBuilder(), var zero: Boolean = true, var sawToken: Boolean = false)

    private fun renderPattern(pattern: String, resolve: (String) -> Pair<String, Boolean>?): String {
        val stack = ArrayDeque(listOf(Frame()))
        fun close() {
            val group = stack.removeLast()
            val parent = stack.last()
            if (!group.sawToken || !group.zero) {
                parent.text.append(group.text)
                parent.sawToken = parent.sawToken || group.sawToken
                if (!group.zero) parent.zero = false
            }
        }
        var i = 0
        while (i < pattern.length) {
            val char = pattern[i]
            val top = stack.last()
            when {
                char == '%' -> {
                    val next = pattern.getOrNull(i + 1)
                    when {
                        next == null -> top.text.append('%')
                        next == '%' || next == '[' || next == ']' -> top.text.append(next)
                        else -> {
                            val token = if (next == '-') pattern.substring(i + 1, minOf(i + 3, pattern.length)) else next.toString()
                            val resolved = resolve(token)
                            if (resolved == null) {
                                top.text.append('%').append(token)
                            } else {
                                top.text.append(resolved.first)
                                top.sawToken = true
                                if (!resolved.second) top.zero = false
                            }
                            i += token.length
                        }
                    }
                    i += if (next == null) 1 else if (next == '%' || next == '[' || next == ']') 2 else 1
                    continue
                }
                char == '[' -> stack.addLast(Frame())
                char == ']' && stack.size > 1 -> close()
                else -> top.text.append(char)
            }
            i++
        }
        while (stack.size > 1) close()
        return stack.first().text.toString()
    }

    fun smallCaps(value: String): String = buildString {
        value.forEach { char -> append(SMALL_CAPS[char.uppercaseChar()] ?: char) }
    }

    /** JavaScript `String(number)` for the values templates produce. */
    fun jsNumber(value: Double): String = when {
        value.isNaN() -> "NaN"
        value.isInfinite() -> if (value > 0) "Infinity" else "-Infinity"
        value == floor(value) && kotlin.math.abs(value) < 1e21 -> BigDecimal(value).toPlainString()
        else -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    }

    /** JavaScript `Math.round`: halves round up. */
    fun jsRound(value: Double): Double = floor(value + 0.5)

    /** JavaScript `parseFloat(x.toFixed(digits))`. */
    fun toFixed(value: Double, digits: Int): Double = BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toDouble()

    const val BINARY = 1024
    const val DECIMAL = 1000
    private const val MILLIS = 1000.0
    private const val SIXTY = 60L
    private val SMALL_CAPS: Map<Char, Char> = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".zip("ᴀʙᴄᴅᴇғɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ").toMap()
}
