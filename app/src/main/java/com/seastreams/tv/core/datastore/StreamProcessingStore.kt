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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val streamJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

/** The master toggle's field inside each key's JSON object; absent = on. The rule classes ignore it as unknown. */
private const val ENABLED_FIELD = "enabled"

/** One key's stored rules ([value] `null` = nothing stored or unreadable) and its master toggle. */
private data class Entry<T>(val value: T?, val enabled: Boolean)

/**
 * One profile's stream filters, sort lists and formatter (PRD §13), stored as JSON under the `p{id}/stream_filters`,
 * `p{id}/stream_sort` and `p{id}/stream_formatter` ScopedKeys. Unlike General Settings these are read for an explicit
 * profile (the one whose streams are resolved), so one profile's choices never reach another's stream list. Each key's
 * object also holds its master toggle as `"enabled": false` while that stage is switched off; the rules are kept as
 * they are. A key whose rules equal the default and whose toggle is on is removed rather than stored; unreadable JSON
 * reads as the default.
 */
@Singleton
class StreamProcessingStore @Inject constructor(private val dataStore: DataStore<Preferences>) {

    private val preferences: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    fun settings(profileId: Long): Flow<StreamProcessingSettings> = preferences
        .map { prefs ->
            val filters = decode(prefs, ProfileSettingsKeys.STREAM_FILTERS, profileId, StreamFilters.serializer())
            val sort = decode(prefs, ProfileSettingsKeys.STREAM_SORT, profileId, StreamSort.serializer())
            val formatter = decode(prefs, ProfileSettingsKeys.STREAM_FORMATTER, profileId, StreamFormatterChoice.serializer())
            StreamProcessingSettings(
                filters = filters.value ?: StreamFilters(),
                sort = sort.value ?: StreamSort(),
                formatter = formatter.value ?: StreamFormatterChoice(),
                filteringEnabled = filters.enabled,
                sortingEnabled = sort.enabled,
                formattingEnabled = formatter.enabled,
            )
        }
        .distinctUntilChanged()

    /** Changes the filters and/or (with a non-null [enabled]) the filtering toggle in one edit; `null` keeps the toggle. */
    suspend fun updateFilters(profileId: Long, enabled: Boolean? = null, transform: (StreamFilters) -> StreamFilters) =
        update(profileId, ProfileSettingsKeys.STREAM_FILTERS, StreamFilters.serializer(), StreamFilters(), enabled) { sanitised(transform(it)) }

    suspend fun updateSort(profileId: Long, enabled: Boolean? = null, transform: (StreamSort) -> StreamSort) =
        update(profileId, ProfileSettingsKeys.STREAM_SORT, StreamSort.serializer(), StreamSort(), enabled) { current ->
            transform(current).let { sort ->
                StreamSort(sort.global.distinctBy { it.key }, sort.movies.distinctBy { it.key }, sort.series.distinctBy { it.key }, sort.anime.distinctBy { it.key })
            }
        }

    suspend fun updateFormatter(profileId: Long, enabled: Boolean? = null, transform: (StreamFormatterChoice) -> StreamFormatterChoice) =
        update(profileId, ProfileSettingsKeys.STREAM_FORMATTER, StreamFormatterChoice.serializer(), StreamFormatterChoice(), enabled) { current ->
            transform(current).let { choice ->
                choice.copy(
                    customName = choice.customName?.take(MAX_TEMPLATE_CHARS)?.takeIf { it.isNotBlank() },
                    customDescription = choice.customDescription?.take(MAX_TEMPLATE_CHARS)?.takeIf { it.isNotBlank() },
                )
            }
        }

    /** `Enable stream filtering`: bypasses the filters without touching them. */
    suspend fun setFilteringEnabled(profileId: Long, enabled: Boolean) = updateFilters(profileId, enabled) { it }

    /** `Enable stream sorting`: bypasses the sort lists without touching them. */
    suspend fun setSortingEnabled(profileId: Long, enabled: Boolean) = updateSort(profileId, enabled) { it }

    /** `Enable stream formatting`: bypasses the formatter without touching it. */
    suspend fun setFormattingEnabled(profileId: Long, enabled: Boolean) = updateFormatter(profileId, enabled) { it }

    private suspend fun <T> update(
        profileId: Long,
        key: ScopedKey<String>,
        serializer: KSerializer<T>,
        default: T,
        enabled: Boolean?,
        transform: (T) -> T,
    ) {
        dataStore.edit { prefs ->
            val current = decode(prefs, key, profileId, serializer)
            write(prefs, key, profileId, serializer, transform(current.value ?: default), default, enabled ?: current.enabled)
        }
    }

    private fun <T> decode(prefs: Preferences, key: ScopedKey<String>, profileId: Long, serializer: KSerializer<T>): Entry<T> {
        val raw = prefs[key.forProfile(profileId)] ?: return Entry(null, true)
        return try {
            val element = streamJson.parseToJsonElement(raw).jsonObject
            val enabled = (element[ENABLED_FIELD] as? JsonPrimitive)?.booleanOrNull ?: true
            Entry(streamJson.decodeFromJsonElement(serializer, element), enabled)
        } catch (e: SerializationException) {
            Entry(null, true)
        } catch (e: IllegalArgumentException) {
            Entry(null, true)
        }
    }

    private fun <T> write(prefs: MutablePreferences, key: ScopedKey<String>, profileId: Long, serializer: KSerializer<T>, value: T, default: T, enabled: Boolean) {
        val storageKey = key.forProfile(profileId)
        if (value == default && enabled) {
            prefs.remove(storageKey)
            return
        }
        val rules = streamJson.encodeToJsonElement(serializer, value).jsonObject
        val stored = if (enabled) rules else JsonObject(rules + (ENABLED_FIELD to JsonPrimitive(false)))
        prefs[storageKey] = stored.toString()
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
