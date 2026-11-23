package com.seastreams.tv.domain.streams.filter

import com.seastreams.tv.domain.streams.ContentKind
import com.seastreams.tv.domain.streams.MovieSeriesRange
import com.seastreams.tv.domain.streams.SizeLimits
import com.seastreams.tv.domain.streams.ValueRange
import com.seastreams.tv.domain.streams.parse.ParsedStream

/**
 * Size and bitrate limits (AIOStreams filterer): a stream's resolution's own range wins over the global one, the movie
 * range applies to movies and the series range to series and anime. Each end of a range is checked only when set,
 * and a stream whose size (or bitrate) is unknown is never removed by it.
 */
object SizeBitrateRules {

    /** Whether [stream] is within the size and bitrate limits for [kind]. */
    fun allows(stream: ParsedStream, kind: ContentKind, size: SizeLimits, bitrate: SizeLimits): Boolean {
        val resolution = stream.release.resolution
        return within(stream.sizeBytes, rangeFor(size, resolution, kind)) &&
            within(stream.bitrate, rangeFor(bitrate, resolution, kind))
    }

    /** The range that applies: the resolution's own (when it sets either end), else the global one; `null` when none. */
    internal fun rangeFor(limits: SizeLimits, resolution: String?, kind: ContentKind): ValueRange? {
        val own = resolution?.let { limits.byResolution[it] }?.pick(kind)?.takeUnless { it.isOpen }
        return own ?: limits.global.pick(kind).takeUnless { it.isOpen }
    }

    private fun MovieSeriesRange.pick(kind: ContentKind): ValueRange = if (kind == ContentKind.MOVIE) movies else series

    private fun within(value: Long?, range: ValueRange?): Boolean =
        value == null || value <= 0 || range == null || value in range
}
