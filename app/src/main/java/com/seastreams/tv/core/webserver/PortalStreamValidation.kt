package com.seastreams.tv.core.webserver

import com.seastreams.tv.core.datastore.StreamProcessingBackupCodec
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.domain.streams.FilterAttribute
import com.seastreams.tv.domain.streams.FilterList
import com.seastreams.tv.domain.streams.MovieSeriesRange
import com.seastreams.tv.domain.streams.RangeRule
import com.seastreams.tv.domain.streams.SizeLimits
import com.seastreams.tv.domain.streams.SortScope
import com.seastreams.tv.domain.streams.StreamFilters
import com.seastreams.tv.domain.streams.StreamSort
import com.seastreams.tv.domain.streams.ValueRange
import com.seastreams.tv.domain.streams.parse.ReleaseRegexTables

/**
 * Checks a portal save before it reaches the store (PRD §13). The portal edits every filter and sort setting the TV
 * does, so a request is held to the same vocabulary: values the TV offers as choices, known resolutions, valid
 * regexes and flags, non-negative ranges and limits. The first problem becomes a [UserFacingException] (`400` with a
 * message that names the field). Accepted rules are then clamped like an imported backup
 * ([StreamProcessingBackupCodec.clamped]) and sanitised by the store, so both paths save identical data.
 */
internal object PortalStreamValidation {
    const val MAX_LIST_ENTRIES = 200
    const val MAX_VALUE_CHARS = 200

    fun filters(filters: StreamFilters): StreamFilters {
        FilterAttribute.entries.forEach { attribute -> checkAttribute(attribute, filters) }
        checkRegex(filters)
        checkRange("Seeders", filters.seeders)
        checkRange("Age", filters.ageHours)
        checkLimits("Size", filters.size)
        checkLimits("Bitrate", filters.bitrate)
        with(filters.limits) {
            listOf("Total results" to global, "Per resolution" to perResolution, "Per quality" to perQuality, "Per addon" to perAddon)
                .forEach { (label, value) -> if (value != null && value < 0) fail("$label can't be negative") }
        }
        if (filters.dedup.enabled && filters.dedup.keys.isEmpty()) fail("Remove duplicates needs at least one thing to match by")
        return StreamProcessingBackupCodec.clamped(filters)
    }

    fun sort(sort: StreamSort): StreamSort {
        SortScope.entries.forEach { scope ->
            val keys = sort.list(scope).map { it.key }
            keys.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let { (key, _) ->
                fail("${scope.label} sorting lists ${key.label} more than once")
            }
        }
        return sort
    }

    /** The first `/pattern/flags` entry whose flags include one the matcher doesn't know (`i m s n`, ignored `g u y`). */
    fun firstUnknownRegexFlag(filters: StreamFilters): String? = with(filters.regex) {
        (required + excluded + included + preferred.map { it.pattern }).firstOrNull { raw ->
            val flags = FLAGGED.matchEntire(raw.trim())?.groupValues?.get(1) ?: return@firstOrNull false
            flags.any { it !in KNOWN_FLAGS }
        }
    }

    private fun checkAttribute(attribute: FilterAttribute, filters: StreamFilters) {
        val filter = filters.attribute(attribute)
        FilterList.entries.forEach { list ->
            val values = filter.list(list)
            val where = "${attribute.label} (${list.label})"
            if (values.size > MAX_LIST_ENTRIES) fail("$where has more than $MAX_LIST_ENTRIES entries")
            val options = attribute.options
            if (options == null) {
                values.firstOrNull { it.length > MAX_VALUE_CHARS }?.let { fail("$where: entries are limited to $MAX_VALUE_CHARS characters") }
            } else {
                values.firstOrNull { it !in options }?.let { fail("$where: \"$it\" is not one of the choices") }
            }
        }
    }

    private fun checkRegex(filters: StreamFilters) {
        val regex = filters.regex
        if (listOf(regex.required, regex.excluded, regex.included, regex.preferred).any { it.size > MAX_LIST_ENTRIES }) {
            fail("Regex lists are limited to $MAX_LIST_ENTRIES patterns")
        }
        regex.preferred.firstOrNull { it.name.length > MAX_VALUE_CHARS }?.let { fail("Regex names are limited to $MAX_VALUE_CHARS characters") }
        firstUnknownRegexFlag(filters)?.let { fail("Unknown regex flag in $it (use i, m, s or n)") }
        StreamProcessingBackupCodec.firstInvalidRegex(filters)?.let { fail("Not a valid regex: $it") }
    }

    private fun checkRange(label: String, rule: RangeRule) {
        checkRange("$label (keep only)", rule.required)
        checkRange("$label (remove)", rule.excluded)
        checkRange("$label (always keep)", rule.included)
    }

    private fun checkLimits(label: String, limits: SizeLimits) {
        limits.byResolution.keys.firstOrNull { it !in ReleaseRegexTables.RESOLUTION_NAMES }?.let { fail("$label: \"$it\" is not a resolution") }
        checkMovieSeries("$label (global)", limits.global)
        limits.byResolution.forEach { (resolution, range) -> checkMovieSeries("$label ($resolution)", range) }
    }

    private fun checkMovieSeries(label: String, range: MovieSeriesRange) {
        checkRange("$label movies", range.movies)
        checkRange("$label series", range.series)
    }

    private fun checkRange(label: String, range: ValueRange) {
        val min = range.min
        val max = range.max
        if ((min != null && min < 0) || (max != null && max < 0)) fail("$label can't be negative")
        if (min != null && max != null && min > max) fail("$label: the minimum is above the maximum")
    }

    private fun fail(message: String): Nothing = throw UserFacingException(message)

    private val FLAGGED = Regex("^/.+/([A-Za-z]+)$", RegexOption.DOT_MATCHES_ALL)
    private const val KNOWN_FLAGS = "gimsuyn"
}
