package com.seastreams.tv.core.datastore

import com.seastreams.tv.domain.streams.MovieSeriesRange
import com.seastreams.tv.domain.streams.RangeRule
import com.seastreams.tv.domain.streams.ResultLimits
import com.seastreams.tv.domain.streams.SizeLimits
import com.seastreams.tv.domain.streams.StreamFilters
import com.seastreams.tv.domain.streams.StreamFormatterChoice
import com.seastreams.tv.domain.streams.StreamProcessingSettings
import com.seastreams.tv.domain.streams.StreamSort
import com.seastreams.tv.domain.streams.ValueRange
import com.seastreams.tv.domain.streams.filter.KeywordMatcher
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The only backup layout so far; a file with any other `version` is refused. */
const val STREAM_BACKUP_VERSION = 1

/** One stage of a backup: its master toggle and the rules it keeps while switched off. */
@Serializable
data class StreamBackupStage<T>(val enabled: Boolean = true, val rules: T)

/**
 * A profile's stream filters, sort lists and formatter (PRD §13 *Backup & copy*), as the portal's `Export JSON`
 * downloads it and `Import JSON` reads it back. Each stage keeps its master toggle beside its rules.
 */
@Serializable
data class StreamProcessingBackup(
    val version: Int,
    val filters: StreamBackupStage<StreamFilters>,
    val sort: StreamBackupStage<StreamSort>,
    val formatter: StreamBackupStage<StreamFormatterChoice>,
) {
    fun toSettings(): StreamProcessingSettings = StreamProcessingSettings(
        filters = filters.rules,
        sort = sort.rules,
        formatter = formatter.rules,
        filteringEnabled = filters.enabled,
        sortingEnabled = sort.enabled,
        formattingEnabled = formatter.enabled,
    )

    companion object {
        fun of(settings: StreamProcessingSettings) = StreamProcessingBackup(
            version = STREAM_BACKUP_VERSION,
            filters = StreamBackupStage(settings.filteringEnabled, settings.filters),
            sort = StreamBackupStage(settings.sortingEnabled, settings.sort),
            formatter = StreamBackupStage(settings.formattingEnabled, settings.formatter),
        )
    }
}

/** A backup that can't be imported; [message] says why, in words the portal can show. */
class InvalidStreamBackupException(message: String) : IllegalArgumentException(message)

/** Encodes backups for download and decodes, validates and clamps uploaded ones. */
object StreamProcessingBackupCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    fun encode(backup: StreamProcessingBackup): String = json.encodeToString(StreamProcessingBackup.serializer(), backup)

    /**
     * Parses [text], refusing anything that isn't a version-[STREAM_BACKUP_VERSION] backup or holds an invalid regex;
     * numeric limits outside their bounds are clamped rather than refused.
     */
    fun decode(text: String): StreamProcessingBackup {
        val backup = try {
            json.decodeFromString(StreamProcessingBackup.serializer(), text)
        } catch (e: SerializationException) {
            throw InvalidStreamBackupException("That isn't a seastreams stream settings file")
        } catch (e: IllegalArgumentException) {
            throw InvalidStreamBackupException("That isn't a seastreams stream settings file")
        }
        return validated(backup)
    }

    /**
     * [backup] checked (version, regexes, template length) with its numeric limits clamped; the full app backup runs
     * every profile's stream settings through this too.
     */
    fun validated(backup: StreamProcessingBackup): StreamProcessingBackup {
        if (backup.version != STREAM_BACKUP_VERSION) {
            throw InvalidStreamBackupException("Unsupported stream settings file version ${backup.version}")
        }
        firstInvalidRegex(backup.filters.rules)?.let { throw InvalidStreamBackupException("Not a valid regex: $it") }
        val formatter = backup.formatter.rules
        if ((formatter.customName?.length ?: 0) > StreamProcessingStore.MAX_TEMPLATE_CHARS ||
            (formatter.customDescription?.length ?: 0) > StreamProcessingStore.MAX_TEMPLATE_CHARS
        ) {
            throw InvalidStreamBackupException("Templates are limited to ${StreamProcessingStore.MAX_TEMPLATE_CHARS} characters")
        }
        return backup.copy(filters = backup.filters.copy(rules = clamped(backup.filters.rules)))
    }

    /** The first non-blank regex (required, excluded, included or preferred) that doesn't compile, if any. */
    fun firstInvalidRegex(filters: StreamFilters): String? = with(filters.regex) {
        (required + excluded + included + preferred.map { it.pattern }).firstOrNull { it.isNotBlank() && !KeywordMatcher.isValid(it) }
    }

    /** Every seeder, age, size, bitrate and result-limit value moved into its valid range. */
    internal fun clamped(filters: StreamFilters): StreamFilters = filters.copy(
        seeders = filters.seeders.clamped(MAX_SEEDERS),
        ageHours = filters.ageHours.clamped(MAX_AGE_HOURS),
        size = filters.size.clamped(MAX_SIZE_BYTES),
        bitrate = filters.bitrate.clamped(MAX_BITRATE),
        limits = filters.limits.let { l -> ResultLimits(l.global.limit(), l.perResolution.limit(), l.perQuality.limit(), l.perAddon.limit()) },
    )

    private fun RangeRule.clamped(max: Long) = RangeRule(required.clamped(max), excluded.clamped(max), included.clamped(max))

    private fun SizeLimits.clamped(max: Long) = SizeLimits(global.clamped(max), byResolution.mapValues { it.value.clamped(max) })

    private fun MovieSeriesRange.clamped(max: Long) = MovieSeriesRange(movies.clamped(max), series.clamped(max))

    /** Both ends into `0..max`; a reversed pair is swapped so the range still means what was typed. */
    private fun ValueRange.clamped(max: Long): ValueRange {
        val low = min?.coerceIn(0, max)
        val high = this.max?.coerceIn(0, max)
        return if (low != null && high != null && low > high) ValueRange(high, low) else ValueRange(low, high)
    }

    /** A positive cap up to [MAX_RESULTS]; zero or negative means unlimited, as the store reads it. */
    private fun Int?.limit(): Int? = this?.takeIf { it > 0 }?.coerceAtMost(MAX_RESULTS)

    const val MAX_SEEDERS = 1_000_000L
    const val MAX_AGE_HOURS = 100L * 365 * 24
    const val MAX_SIZE_BYTES = 100_000_000_000_000L
    const val MAX_BITRATE = 1_000_000_000_000L
    const val MAX_RESULTS = 1_000
}
