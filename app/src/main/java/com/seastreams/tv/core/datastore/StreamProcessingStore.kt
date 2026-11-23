package com.seastreams.tv.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.seastreams.tv.domain.streams.AttributeFilter
import com.seastreams.tv.domain.streams.FilterAttribute
import com.seastreams.tv.domain.streams.ResultLimits
import com.seastreams.tv.domain.streams.StreamFilters
import com.seastreams.tv.domain.streams.StreamFormatterChoice
import com.seastreams.tv.domain.streams.StreamProcessingSettings
import com.seastreams.tv.domain.streams.StreamSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val streamJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

/**
 * One profile's stream filters, sort lists and formatter (PRD §13), stored as JSON under the `p{id}/stream_filters`,
 * `p{id}/stream_sort` and `p{id}/stream_formatter` ScopedKeys. Unlike General Settings these are read for an explicit
 * profile (the one whose streams are resolved), so one profile's choices never reach another's stream list. A value
 * equal to its default is removed rather than stored; unreadable JSON reads as the default.
 */
@Singleton
class StreamProcessingStore @Inject constructor(private val dataStore: DataStore<Preferences>) {

    private val preferences: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    fun settings(profileId: Long): Flow<StreamProcessingSettings> = preferences
        .map { prefs ->
            StreamProcessingSettings(
                filters = decode(prefs, ProfileSettingsKeys.STREAM_FILTERS, profileId, StreamFilters.serializer()) ?: StreamFilters(),
                sort = decode(prefs, ProfileSettingsKeys.STREAM_SORT, profileId, StreamSort.serializer()) ?: StreamSort(),
                formatter = decode(prefs, ProfileSettingsKeys.STREAM_FORMATTER, profileId, StreamFormatterChoice.serializer())
                    ?: StreamFormatterChoice(),
            )
        }
        .distinctUntilChanged()

    suspend fun updateFilters(profileId: Long, transform: (StreamFilters) -> StreamFilters) = edit { prefs ->
        val current = decode(prefs, ProfileSettingsKeys.STREAM_FILTERS, profileId, StreamFilters.serializer()) ?: StreamFilters()
        write(prefs, ProfileSettingsKeys.STREAM_FILTERS, profileId, StreamFilters.serializer(), sanitised(transform(current)), StreamFilters())
    }

    suspend fun updateSort(profileId: Long, transform: (StreamSort) -> StreamSort) = edit { prefs ->
        val current = decode(prefs, ProfileSettingsKeys.STREAM_SORT, profileId, StreamSort.serializer()) ?: StreamSort()
        val updated = transform(current).let { sort ->
            StreamSort(sort.global.distinctBy { it.key }, sort.movies.distinctBy { it.key }, sort.series.distinctBy { it.key }, sort.anime.distinctBy { it.key })
        }
        write(prefs, ProfileSettingsKeys.STREAM_SORT, profileId, StreamSort.serializer(), updated, StreamSort())
    }

    suspend fun updateFormatter(profileId: Long, transform: (StreamFormatterChoice) -> StreamFormatterChoice) = edit { prefs ->
        val current = decode(prefs, ProfileSettingsKeys.STREAM_FORMATTER, profileId, StreamFormatterChoice.serializer()) ?: StreamFormatterChoice()
        val updated = transform(current).let { choice ->
            choice.copy(
                customName = choice.customName?.take(MAX_TEMPLATE_CHARS)?.takeIf { it.isNotBlank() },
                customDescription = choice.customDescription?.take(MAX_TEMPLATE_CHARS)?.takeIf { it.isNotBlank() },
            )
        }
        write(prefs, ProfileSettingsKeys.STREAM_FORMATTER, profileId, StreamFormatterChoice.serializer(), updated, StreamFormatterChoice())
    }

    private fun <T> decode(prefs: Preferences, key: ScopedKey<String>, profileId: Long, serializer: KSerializer<T>): T? {
        val raw = prefs[key.forProfile(profileId)] ?: return null
        return try {
            streamJson.decodeFromString(serializer, raw)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun <T> write(prefs: MutablePreferences, key: ScopedKey<String>, profileId: Long, serializer: KSerializer<T>, value: T, default: T) {
        val storageKey = key.forProfile(profileId)
        if (value == default) prefs.remove(storageKey) else prefs[storageKey] = streamJson.encodeToString(serializer, value)
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    companion object {
        const val MAX_TEMPLATE_CHARS = 8_000

        /** Trims and de-duplicates every list and drops non-positive result limits. */
        internal fun sanitised(filters: StreamFilters): StreamFilters {
            fun AttributeFilter.tidy() = AttributeFilter(clean(required), clean(excluded), clean(included), clean(preferred))
            fun Int?.positive() = this?.takeIf { it > 0 }
            var result = filters
            FilterAttribute.entries.forEach { attribute ->
                result = result.withAttribute(attribute, result.attribute(attribute).tidy())
            }
            return result.copy(
                regex = result.regex.copy(
                    required = clean(result.regex.required),
                    excluded = clean(result.regex.excluded),
                    included = clean(result.regex.included),
                    preferred = result.regex.preferred.filter { it.pattern.isNotBlank() }.distinctBy { it.pattern },
                ),
                limits = ResultLimits(result.limits.global.positive(), result.limits.perResolution.positive(), result.limits.perQuality.positive(), result.limits.perAddon.positive()),
                dedup = result.dedup.copy(keys = result.dedup.keys.distinct()),
            )
        }

        private fun clean(values: List<String>): List<String> = values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
