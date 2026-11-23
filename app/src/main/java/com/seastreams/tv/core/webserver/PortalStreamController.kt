package com.seastreams.tv.core.webserver

import com.seastreams.tv.core.datastore.StreamProcessingStore
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.domain.streams.FilterAttribute
import com.seastreams.tv.domain.streams.FormatterId
import com.seastreams.tv.domain.streams.SortKey
import com.seastreams.tv.domain.streams.StreamFilters
import com.seastreams.tv.domain.streams.StreamFormatterChoice
import com.seastreams.tv.domain.streams.StreamSort
import com.seastreams.tv.domain.streams.filter.KeywordMatcher
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
    val resolutions: List<String>,
    val attributes: List<StreamAttributeDto>,
    val sortKeys: List<StreamOptionDto>,
    val formatters: List<StreamOptionDto>,
    val presets: Map<String, FormatterTemplateDto>,
)

@Serializable
data class StreamAttributeDto(val key: String, val label: String, val options: List<String>?)

@Serializable
data class StreamOptionDto(val id: String, val label: String)

@Serializable
data class FormatterTemplateDto(val name: String, val description: String)

@Serializable
data class StreamFiltersRequest(val profileId: Long, val filters: StreamFilters)

@Serializable
data class StreamSortRequest(val profileId: Long, val sort: StreamSort)

@Serializable
data class StreamFormatterRequest(val profileId: Long, val formatter: StreamFormatterChoice)

/** `POST /api/streams/format-preview`: templates to try, optionally on the user's own release name. */
@Serializable
data class FormatPreviewRequest(val name: String, val description: String, val filename: String? = null)

@Serializable
data class FormatPreviewResponse(val name: String, val description: String)

/**
 * Portal actions for per-profile stream filters, sorting and formatter (PRD §13). Every write names its profile, which
 * must exist, and only touches that profile's `p{id}/stream_*` keys. Regexes are validated before saving.
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
            resolutions = ReleaseRegexTables.RESOLUTION_NAMES,
            attributes = FilterAttribute.entries.map { StreamAttributeDto(it.name, it.label, it.options) },
            sortKeys = SortKey.entries.map { StreamOptionDto(it.name, it.label) },
            formatters = FormatterId.entries.map { StreamOptionDto(it.name, it.label) },
            presets = FormatterId.entries.mapNotNull { id ->
                PredefinedFormatters.templates(id)?.let { id.name to FormatterTemplateDto(it.name, it.description) }
            }.toMap(),
        )
    }

    suspend fun saveFilters(request: StreamFiltersRequest): String {
        val name = requireProfile(request.profileId)
        val regex = request.filters.regex
        (regex.required + regex.excluded + regex.included + regex.preferred.map { it.pattern })
            .firstOrNull { it.isNotBlank() && !KeywordMatcher.isValid(it) }
            ?.let { throw UserFacingException("Not a valid regex: $it") }
        store.updateFilters(request.profileId) { request.filters }
        return "Stream filters saved for $name"
    }

    suspend fun saveSort(request: StreamSortRequest): String {
        val name = requireProfile(request.profileId)
        store.updateSort(request.profileId) { request.sort }
        return "Stream sorting saved for $name"
    }

    suspend fun saveFormatter(request: StreamFormatterRequest): String {
        val name = requireProfile(request.profileId)
        val formatter = request.formatter
        if ((formatter.customName?.length ?: 0) > StreamProcessingStore.MAX_TEMPLATE_CHARS ||
            (formatter.customDescription?.length ?: 0) > StreamProcessingStore.MAX_TEMPLATE_CHARS
        ) {
            throw UserFacingException("Templates are limited to ${StreamProcessingStore.MAX_TEMPLATE_CHARS} characters")
        }
        store.updateFormatter(request.profileId) { formatter }
        return "Stream formatter saved for $name"
    }

    /** The sample release (or [FormatPreviewRequest.filename]) rendered with the request's templates. */
    fun preview(request: FormatPreviewRequest): FormatPreviewResponse {
        val templates = FormatterTemplates(request.name.take(StreamProcessingStore.MAX_TEMPLATE_CHARS), request.description.take(StreamProcessingStore.MAX_TEMPLATE_CHARS))
        val display = FormatterPreview.render(templates, clock.millis(), request.filename?.take(MAX_FILENAME_CHARS))
        return FormatPreviewResponse(display.name, display.description)
    }

    private suspend fun requireProfile(profileId: Long): String =
        profiles.observeProfiles().first().firstOrNull { it.id == profileId }?.name ?: throw UserFacingException("That profile no longer exists")

    private companion object {
        const val MAX_FILENAME_CHARS = 512
    }
}
