package com.seastreams.tv.core.webserver

/** What a `Range` header asks of a resource of known size. */
sealed interface ByteRange {
    /** No (usable) range: send everything with `200`. */
    data object Full : ByteRange

    /** `206` with bytes `[start, endInclusive]`. */
    data class Partial(val start: Long, val endInclusive: Long) : ByteRange {
        val length: Long get() = endInclusive - start + 1
    }

    /** `416`: the range starts past the end. */
    data object Unsatisfiable : ByteRange

    companion object {
        private val SINGLE = Regex("^\\s*(\\d*)\\s*-\\s*(\\d*)\\s*$")

        /**
         * Parses `bytes=a-b`, `bytes=a-` and `bytes=-n` (RFC 9110). Other units, syntax errors and multi-range
         * requests are answered in full (a server may ignore `Range`), so only single ranges get `206`.
         */
        fun parse(header: String?, size: Long): ByteRange {
            val spec = header?.trim() ?: return Full
            if (!spec.startsWith("bytes=", ignoreCase = true)) return Full
            val ranges = spec.substring(6)
            if (',' in ranges) return Full
            val match = SINGLE.matchEntire(ranges) ?: return Full
            val (first, last) = match.destructured
            if (first.isEmpty() && last.isEmpty()) return Full
            val firstValue = first.toLongOrNull()
            val lastValue = last.toLongOrNull()
            if ((first.isNotEmpty() && firstValue == null) || (last.isNotEmpty() && lastValue == null)) return Full
            return when {
                firstValue == null -> {
                    val suffix = lastValue ?: return Full
                    if (suffix == 0L || size == 0L) Unsatisfiable else Partial((size - suffix).coerceAtLeast(0), size - 1)
                }
                lastValue != null && lastValue < firstValue -> Full
                firstValue >= size -> Unsatisfiable
                else -> Partial(firstValue, minOf(lastValue ?: (size - 1), size - 1))
            }
        }
    }
}
