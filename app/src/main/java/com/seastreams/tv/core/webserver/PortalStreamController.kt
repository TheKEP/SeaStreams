package com.seastreams.tv.core.webserver

import com.seastreams.tv.core.datastore.InvalidStreamBackupException
import com.seastreams.tv.core.datastore.StreamProcessingBackupCodec
import com.seastreams.tv.core.datastore.StreamProcessingStore
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.backup.BackupFileNames
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.domain.streams.DedupKey
import com.seastreams.tv.domain.streams.DedupMode
import com.seastreams.tv.domain.streams.FilterAttribute
import com.seastreams.tv.domain.streams.FilterList
import com.seastreams.tv.domain.streams.FormatterId
import com.seastreams.tv.domain.streams.SortDirection
import com.seastreams.tv.domain.streams.SortKey
import com.seastreams.tv.domain.streams.SortScope
import com.seastreams.tv.domain.streams.StreamFilters
import com.seastreams.tv.domain.streams.StreamFormatterChoice
import com.seastreams.tv.domain.streams.StreamSort
import com.seastreams.tv.domain.streams.format.FormatterPreview
import com.seastreams.tv.domain.streams.format.FormatterTemplates
import com.seastreams.tv.domain.streams.format.PredefinedFormatters
import com.seastreams.tv.domain.streams.parse.ReleaseRegexTables
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** `GET /api/streams?profileId=…`: one profile's stream processing plus the vocabularies the forms offer. */
@Serializable
data class StreamPortalState(
    val profileId: Long,
    val filters: StreamFilters,
    val sort: StreamSort,
    val formatter: StreamFormatterChoice,
    val filteringEnabled: Boolean,
    val sortingEnabled: Boolean,
    val formattingEnabled: Boolean,
    val resolutions: List<String>,
    val attributes: List<StreamAttributeDto>,
    val sortKeys: List<StreamOptionDto>,
    val formatters: List<StreamOptionDto>,
    val presets: Map<String, FormatterTemplateDto>,
    val filterLists: List<StreamOptionDto> = emptyList(),
    val sortScopes: List<StreamOptionDto> = emptyList(),
    val sortDirections: List<StreamOptionDto> = emptyList(),
    val dedupModes: List<StreamOptionDto> = emptyList(),
    val dedupKeys: List<StreamOptionDto> = emptyList(),
)

/** One filter group: [key] is the [FilterAttribute] name, [field] its [StreamFilters] property, [options] `null` for free text. */
@Serializable
data class StreamAttributeDto(val key: String, val label: String, val options: List<String>?, val field: String = "")

@Serializable
data class StreamOptionDto(val id: String, val label: String, val description: String = "")

@Serializable
data class FormatterTemplateDto(val name: String, val description: String)

/**
 * The save requests take the rules and/or the stage's master toggle; `null` leaves that part as stored, so a portal
 * checkbox posts only its flag. A request with neither is refused.
 */
@Serializable
data class StreamFiltersRequest(val profileId: Long, val filters: StreamFilters? = null, val filteringEnabled: Boolean? = null)

@Serializable
data class StreamSortRequest(val profileId: Long, val sort: StreamSort? = null, val sortingEnabled: Boolean? = null)

@Serializable
data class StreamFormatterRequest(val profileId: Long, val formatter: StreamFormatterChoice? = null, val formattingEnabled: Boolean? = null)

/** `POST /api/streams/format-preview`: templates to try, optionally on the user's own release name. */
@Serializable
data class FormatPreviewRequest(val name: String, val description: String, val filename: String? = null)

@Serializable
data class FormatPreviewResponse(val name: String, val description: String)

/** `GET /api/streams/export`: the download's file name and its JSON body. */
data class StreamBackupFile(val fileName: String, val json: String)

/**
 * Portal actions for per-profile stream filters, sorting and formatter (PRD §13). Every write names its profile, which
 * must exist, and only touches that profile's `p{id}/stream_*` keys. Every filter and sort setting the TV offers is
 * editable here; saves are validated and clamped first ([PortalStreamValidation]).
 */
@Singleton
class PortalStreamController @Inject constructor(
    private val profiles: ProfileRepository,
    private val store: StreamProcessingStore,
    private val clock: Clock,
) {

    suspend fun state(profileId: Long): StreamPortalState {
        requireProfile(profileId)
        val settings = store.settings(profileId).first()
        return StreamPortalState(
            profileId = profileId,
            filters = settings.filters,
            sort = settings.sort,
            formatter = settings.formatter,
            filteringEnabled = settings.filteringEnabled,
            sortingEnabled = settings.sortingEnabled,
            formattingEnabled = settings.formattingEnabled,
            resolutions = ReleaseRegexTables.RESOLUTION_NAMES,
            attributes = FilterAttribute.entries.map { StreamAttributeDto(it.name, it.label, it.options, it.field) },
            sortKeys = SortKey.entries.map { StreamOptionDto(it.name, it.label, it.description) },
            formatters = FormatterId.entries.map { StreamOptionDto(it.name, it.label) },
            presets = FormatterId.entries.mapNotNull { id ->
                PredefinedFormatters.templates(id)?.let { id.name to FormatterTemplateDto(it.name, it.description) }
            }.toMap(),
            filterLists = FilterList.entries.map { StreamOptionDto(it.name.lowercase(), it.label) },
            sortScopes = SortScope.entries.map { StreamOptionDto(it.name.lowercase(), it.label) },
            sortDirections = SortDirection.entries.map { StreamOptionDto(it.name, it.label) },
            dedupModes = DedupMode.entries.map { StreamOptionDto(it.name, it.label) },
            dedupKeys = DedupKey.entries.map { StreamOptionDto(it.name, it.label) },
        )
    }

    suspend fun saveFilters(request: StreamFiltersRequest): String {
        requireChange(request.filters, request.filteringEnabled)
        val name = requireProfile(request.profileId)
        val filters = request.filters?.let(PortalStreamValidation::filters)
        store.updateFilters(request.profileId, request.filteringEnabled) { filters ?: it }
        return if (filters == null) toggled("Stream filtering", request.filteringEnabled, name) else "Stream filters saved for $name"
    }

    suspend fun saveSort(request: StreamSortRequest): String {
        requireChange(request.sort, request.sortingEnabled)
        val name = requireProfile(request.profileId)
        val sort = request.sort?.let(PortalStreamValidation::sort)
        store.updateSort(request.profileId, request.sortingEnabled) { sort ?: it }
        return if (sort == null) toggled("Stream sorting", request.sortingEnabled, name) else "Stream sorting saved for $name"
    }

    suspend fun saveFormatter(request: StreamFormatterRequest): String {
        val formatter = request.formatter
        requireChange(formatter, request.formattingEnabled)
        val name = requireProfile(request.profileId)
        if ((formatter?.customName?.length ?: 0) > StreamProcessingStore.MAX_TEMPLATE_CHARS ||
            (formatter?.customDescription?.length ?: 0) > StreamProcessingStore.MAX_TEMPLATE_CHARS
        ) {
            throw UserFacingException("Templates are limited to ${StreamProcessingStore.MAX_TEMPLATE_CHARS} characters")
        }
        store.updateFormatter(request.profileId, request.formattingEnabled) { formatter ?: it }
        return if (formatter == null) toggled("Stream formatting", request.formattingEnabled, name) else "Stream formatter saved for $name"
    }

    /** [profileId]'s filters, sort lists and formatter, with their master toggles, as a downloadable JSON backup. */
    suspend fun exportBackup(profileId: Long): StreamBackupFile {
        val name = requireProfile(profileId)
        return StreamBackupFile(backupFileName(name), StreamProcessingBackupCodec.encode(store.exportBackup(profileId)))
    }

    /** Validates [json] (version, regexes, template length; numbers are clamped) and replaces [profileId]'s stages. */
    suspend fun importBackup(profileId: Long, json: String): String {
        val name = requireProfile(profileId)
        val backup = try {
            StreamProcessingBackupCodec.decode(json)
        } catch (e: InvalidStreamBackupException) {
            throw UserFacingException(e.message.orEmpty())
        }
        store.importBackup(profileId, backup)
        return "Stream settings imported for $name"
    }

    /** The sample release (or [FormatPreviewRequest.filename]) rendered with the request's templates. */
    fun preview(request: FormatPreviewRequest): FormatPreviewResponse {
        val templates = FormatterTemplates(request.name.take(StreamProcessingStore.MAX_TEMPLATE_CHARS), request.description.take(StreamProcessingStore.MAX_TEMPLATE_CHARS))
        val display = FormatterPreview.render(templates, clock.millis(), request.filename?.take(MAX_FILENAME_CHARS))
        return FormatPreviewResponse(display.name, display.description)
    }

    private fun requireChange(rules: Any?, enabled: Boolean?) {
        if (rules == null && enabled == null) throw UserFacingException("Nothing to save")
    }

    private fun toggled(stage: String, enabled: Boolean?, profile: String): String =
        "$stage turned ${if (enabled == true) "on" else "off"} for $profile"

    private suspend fun requireProfile(profileId: Long): String =
        profiles.observeProfiles().first().firstOrNull { it.id == profileId }?.name ?: throw UserFacingException("That profile no longer exists")

    internal companion object {
        const val MAX_FILENAME_CHARS = 512

        /** `seastreams-stream-settings-<profile>.json`, the profile name reduced to `a-z`, `0-9` and `-`. */
        fun backupFileName(profileName: String): String = "seastreams-stream-settings-${BackupFileNames.profileSlug(profileName)}.json"
    }
}
