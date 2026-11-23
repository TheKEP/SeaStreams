package com.seastreams.tv.domain.streams.sort

import com.seastreams.tv.domain.streams.ContentKind
import com.seastreams.tv.domain.streams.FilterAttribute
import com.seastreams.tv.domain.streams.SortCriterion
import com.seastreams.tv.domain.streams.SortDirection
import com.seastreams.tv.domain.streams.SortKey
import com.seastreams.tv.domain.streams.StreamFilters
import com.seastreams.tv.domain.streams.StreamSort
import com.seastreams.tv.domain.streams.filter.StreamCandidate
import com.seastreams.tv.domain.streams.filter.StreamFilterEngine
import com.seastreams.tv.domain.streams.parse.ParsedStream

/**
 * The sort stage (AIOStreams `sorter.ts`, `aio-sorting.png`). The list for [kind] (Movies / Series / Anime when
 * non-empty, else Global) is compared criterion by criterion. Each criterion yields a number, flipped by its direction:
 * - Numeric criteria (size, bitrate, seeders, age): `Descending` = larger first.
 * - Preferred-list criteria rank by position in the matching *Preferred* list (its first entry first when
 *   `Descending`); a stream with no preferred value ranks after every one that has one. A multi-valued attribute
 *   ranks by its best (earliest) preferred value.
 * - `Addon` follows [addonOrder]; `Keyword` puts preferred-keyword matches first; `Regex patterns` ranks by the first
 *   preferred regex that matched.
 * An empty list leaves the order untouched, and the sort is stable, so ties keep their addon order.
 */
class StreamSorter(
    private val sort: StreamSort,
    private val filters: StreamFilters,
    private val addonOrder: List<String>,
) {

    fun sort(candidates: List<StreamCandidate>, kind: ContentKind): List<StreamCandidate> {
        val criteria = sort.effective(kind)
        if (criteria.isEmpty()) return candidates
        val keys = candidates.associateWith { candidate -> criteria.map { keyValue(candidate, it) } }
        return candidates.sortedWith { a, b -> compareKeys(keys.getValue(a), keys.getValue(b)) }
    }

    /** Smaller sorts first (AIOStreams' `multiplier * value` keys). */
    internal fun keyValue(candidate: StreamCandidate, criterion: SortCriterion): Double {
        val stream = candidate.parsed
        val multiplier = if (criterion.direction == SortDirection.ASC) 1.0 else -1.0
        val value = when (criterion.key) {
            SortKey.SIZE -> (stream.sizeBytes ?: 0L).toDouble()
            SortKey.BITRATE -> (stream.bitrate ?: 0L).toDouble()
            SortKey.SEEDERS -> (stream.seeders ?: 0).toDouble()
            SortKey.AGE -> stream.ageHours ?: 0.0
            SortKey.KEYWORD -> if (stream.keywordMatched) 1.0 else 0.0
            SortKey.REGEX -> -(stream.regexIndex?.toDouble() ?: Double.POSITIVE_INFINITY)
            SortKey.ADDON -> -indexOrInfinity(addonOrder.indexOf(candidate.source.addonUrl))
            else -> preferredRank(stream, attributeOf(criterion.key))
        }
        return multiplier * value
    }

    /** `-index` of the stream's best value in the attribute's *Preferred* list; `0` when that list is empty. */
    private fun preferredRank(stream: ParsedStream, attribute: FilterAttribute): Double {
        val preferred = filters.attribute(attribute).preferred
        if (preferred.isEmpty()) return 0.0
        val best = StreamFilterEngine.values(stream, attribute)
            .map { value -> preferred.indexOfFirst { it.equals(value, ignoreCase = true) } }
            .filter { it >= 0 }
            .minOrNull() ?: -1
        return -indexOrInfinity(best)
    }

    private fun indexOrInfinity(index: Int): Double = if (index < 0) Double.POSITIVE_INFINITY else index.toDouble()

    companion object {
        internal fun compareKeys(a: List<Double>, b: List<Double>): Int {
            for (i in a.indices) {
                val cmp = a[i].compareTo(b[i])
                if (cmp != 0) return cmp
            }
            return 0
        }

        /** The filter attribute whose *Preferred* list a preferred-list sort key ranks by. */
        internal fun attributeOf(key: SortKey): FilterAttribute = when (key) {
            SortKey.QUALITY -> FilterAttribute.QUALITY
            SortKey.RESOLUTION -> FilterAttribute.RESOLUTION
            SortKey.LANGUAGE -> FilterAttribute.LANGUAGE
            SortKey.SUBTITLE -> FilterAttribute.SUBTITLE
            SortKey.VISUAL_TAG -> FilterAttribute.VISUAL_TAG
            SortKey.AUDIO_TAG -> FilterAttribute.AUDIO_TAG
            SortKey.AUDIO_CHANNEL -> FilterAttribute.AUDIO_CHANNEL
            SortKey.ENCODE -> FilterAttribute.ENCODE
            SortKey.STREAM_TYPE -> FilterAttribute.STREAM_TYPE
            SortKey.RELEASE_GROUP -> FilterAttribute.RELEASE_GROUP
            else -> error("$key is not a preferred-list criterion")
        }
    }
}
