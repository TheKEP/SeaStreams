package com.seastreams.tv.usenet.stream

import java.util.TreeMap

/**
 * Finds the segment holding a byte offset (ARCHITECTURE §10 Locating bytes). NZB sizes are the *encoded* sizes,
 * so positions come from the decoded segments themselves (`=ypart begin`):
 * - once a non-last segment proves a uniform grid (`begin == index × length`, agreeing with segment 0), the part
 *   size locks and lookups are arithmetic; a later contradiction unlocks it;
 * - otherwise an interpolation search between the nearest measured segments narrows the bracket each probe;
 * - [adoptGrid] takes a sibling's proven grid (RAR middle volumes of the same set), so no probe is needed.
 */
class SegmentLocator(val segmentCount: Int, val fileSize: Long) {
    private val measured = TreeMap<Long, Pair<Int, Int>>() // begin → (index, length)
    private val byIndex = HashMap<Int, Pair<Long, Int>>()

    @Volatile
    var lockedPartSize: Long? = null
        private set

    init {
        require(segmentCount > 0 && fileSize >= 0)
    }

    @Synchronized
    fun record(index: Int, begin: Long, length: Int) {
        if (byIndex[index]?.first == begin) return
        byIndex[index] = begin to length
        measured[begin] = index to length
        val locked = lockedPartSize
        if (locked != null) {
            val last = index == segmentCount - 1
            val fits = begin == index * locked && (last || length.toLong() == locked)
            if (!fits) lockedPartSize = null
            return
        }
        val first = byIndex[0]
        if (index > 0 && index < segmentCount - 1 && length > 0 && begin == index.toLong() * length &&
            (first == null || first.second == length)
        ) {
            lockedPartSize = length.toLong()
        } else if (index == 0 && segmentCount > 1 && length > 0) {
            val other = byIndex.entries.firstOrNull { it.key in 1 until segmentCount - 1 }
            if (other != null && other.value.first == other.key.toLong() * length && other.value.second == length) {
                lockedPartSize = length.toLong()
            }
        }
    }

    @Synchronized
    fun adoptGrid(partSize: Long) {
        if (partSize > 0) lockedPartSize = partSize
    }

    /** The first index to probe for [offset] within the remaining bracket [low]..[high]. */
    @Synchronized
    fun guess(offset: Long, low: Int = 0, high: Int = segmentCount - 1): Int {
        if (low >= high) return low
        lockedPartSize?.let { return (offset / it).toInt().coerceIn(low, high) }
        val below = measured.floorEntry(offset)
        val above = measured.higherEntry(offset)
        val estimate = when {
            below != null && offset < below.key + below.value.second -> below.value.first
            below != null && above != null -> interpolate(offset, below.key, below.value.first, above.key, above.value.first)
            below != null -> interpolate(offset, below.key, below.value.first, fileSize, segmentCount)
            above != null -> interpolate(offset, 0, 0, above.key, above.value.first)
            else -> interpolate(offset, 0, 0, fileSize, segmentCount)
        }
        return estimate.coerceIn(low, high)
    }

    private fun interpolate(offset: Long, fromByte: Long, fromIndex: Int, toByte: Long, toIndex: Int): Int {
        if (toByte <= fromByte) return fromIndex
        val span = (toIndex - fromIndex).toDouble()
        return fromIndex + ((offset - fromByte) * span / (toByte - fromByte)).toInt()
    }
}
