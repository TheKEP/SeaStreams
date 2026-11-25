package com.seastreams.tv.core.webserver

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.datastore.StreamProcessingStore
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.domain.streams.AttributeFilter
import com.seastreams.tv.domain.streams.DedupKey
import com.seastreams.tv.domain.streams.DedupMode
import com.seastreams.tv.domain.streams.DedupSettings
import com.seastreams.tv.domain.streams.FilterAttribute
import com.seastreams.tv.domain.streams.FilterList
import com.seastreams.tv.domain.streams.MovieSeriesRange
import com.seastreams.tv.domain.streams.NamedRegex
import com.seastreams.tv.domain.streams.RangeRule
import com.seastreams.tv.domain.streams.RegexRules
import com.seastreams.tv.domain.streams.ResultLimits
import com.seastreams.tv.domain.streams.SizeLimits
import com.seastreams.tv.domain.streams.SortCriterion
import com.seastreams.tv.domain.streams.SortDirection
import com.seastreams.tv.domain.streams.SortKey
import com.seastreams.tv.domain.streams.SortScope
import com.seastreams.tv.domain.streams.StreamFilters
import com.seastreams.tv.domain.streams.StreamSort
import com.seastreams.tv.domain.streams.ValueRange
import com.seastreams.tv.domain.streams.parse.ReleaseRegexTables
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock

/**
 * The portal edits every stream filter and sort setting the TV offers (PRD §13): a save with every field set comes
 * back unchanged from `GET /api/streams`, and anything outside the TV's vocabulary is refused with a `400`.
 */
class PortalStreamFullRoundTripTest {

    @TempDir
    lateinit var dir: File

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var scope: CoroutineScope
    private lateinit var store: StreamProcessingStore
    private lateinit var portal: LocalPortalServer
    private lateinit var browser: HttpClient
    private lateinit var base: String

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = StreamProcessingStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") })
        val profiles = mockk<ProfileRepository> {
            every { observeProfiles() } returns flowOf(listOf(profile(1, "Ken"), profile(2, "Kids")))
        }
        portal = LocalPortalServer(mockk(relaxed = true), mockk(relaxed = true), streams = PortalStreamController(profiles, store, Clock.systemUTC()))
        val port = requireNotNull(portal.start(port = 0, host = "127.0.0.1"))
        base = "http://127.0.0.1:$port"
        browser = HttpClient(OkHttp)
    }

    @AfterEach
    fun tearDown() {
        portal.stop()
        browser.close()
        scope.cancel()
    }

    private suspend fun post(path: String, body: String): HttpResponse = browser.post(base + path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun state(profileId: Long): StreamPortalState = json.decodeFromString(browser.get("$base/api/streams?profileId=$profileId").bodyAsText())

    private suspend fun saveFilters(filters: StreamFilters): HttpResponse =
        post("/api/streams/filters", json.encodeToString(StreamFiltersRequest.serializer(), StreamFiltersRequest(1, filters)))

    private suspend fun saveSort(sort: StreamSort): HttpResponse =
        post("/api/streams/sort", json.encodeToString(StreamSortRequest.serializer(), StreamSortRequest(1, sort)))

    @Test
    fun `every filter field set on the phone round-trips unchanged for that profile only`() = runTest {
        val filters = everyFilter()
        val saved = saveFilters(filters)
        assertEquals(200, saved.status.value, saved.bodyAsText())

        assertEquals(filters, state(1).filters)
        assertEquals(filters, store.settings(1).first().filters)
        assertEquals(StreamFilters(), state(2).filters)
    }

    @Test
    fun `all four sort lists with their directions round-trip`() = runTest {
        val sort = StreamSort(
            global = listOf(SortCriterion(SortKey.RESOLUTION), SortCriterion(SortKey.SIZE, SortDirection.ASC)),
            movies = listOf(SortCriterion(SortKey.QUALITY), SortCriterion(SortKey.SEEDERS, SortDirection.ASC), SortCriterion(SortKey.REGEX)),
            series = listOf(SortCriterion(SortKey.AGE, SortDirection.ASC)),
            anime = SortKey.entries.map { SortCriterion(it, if (it.ordinal % 2 == 0) SortDirection.DESC else SortDirection.ASC) },
        )
        val saved = saveSort(sort)
        assertEquals(200, saved.status.value, saved.bodyAsText())
        assertEquals(sort, state(1).sort)
        assertEquals(StreamSort(), state(2).sort)
    }

    @Test
    fun `the state carries every vocabulary the portal forms need`() = runTest {
        val st = state(1)
        assertEquals(FilterList.entries.map { it.name.lowercase() }, st.filterLists.map { it.id })
        assertEquals(SortScope.entries.map { it.name.lowercase() }, st.sortScopes.map { it.id })
        assertEquals(SortDirection.entries.map { it.name }, st.sortDirections.map { it.id })
        assertEquals(DedupMode.entries.map { it.name }, st.dedupModes.map { it.id })
        assertEquals(DedupKey.entries.map { it.name }, st.dedupKeys.map { it.id })
        assertTrue(st.sortKeys.all { it.description.isNotBlank() })
        // Each group names its StreamFilters property, which the script reads and writes.
        val encoded = json.parseToJsonElement(browser.get("$base/api/streams?profileId=1").bodyAsText()).toString()
        st.attributes.forEach { assertTrue(encoded.contains("\"${it.field}\":{"), it.field) }
    }

    @Test
    fun `values outside the TV vocabulary are refused and nothing is saved`() = runTest {
        val resolution = ReleaseRegexTables.RESOLUTION_NAMES.first()
        val refused = listOf(
            StreamFilters(resolutions = AttributeFilter(required = listOf("9000p"))),
            StreamFilters(languages = AttributeFilter(preferred = listOf("Klingon"))),
            StreamFilters(size = SizeLimits(byResolution = mapOf("huge" to MovieSeriesRange()))),
            StreamFilters(seeders = RangeRule(required = ValueRange(min = -1))),
            StreamFilters(ageHours = RangeRule(excluded = ValueRange(min = 50, max = 10))),
            StreamFilters(bitrate = SizeLimits(byResolution = mapOf(resolution to MovieSeriesRange(series = ValueRange(max = -5))))),
            StreamFilters(regex = RegexRules(excluded = listOf("/cam/q"))),
            StreamFilters(regex = RegexRules(preferred = listOf(NamedRegex("/([bad/i", "Broken")))),
            StreamFilters(limits = ResultLimits(perAddon = -2)),
            StreamFilters(dedup = DedupSettings(enabled = true, keys = emptyList())),
            StreamFilters(keywords = AttributeFilter(required = listOf("x".repeat(PortalStreamValidation.MAX_VALUE_CHARS + 1)))),
        )
        refused.forEach { filters ->
            val response = saveFilters(filters)
            assertEquals(400, response.status.value, "$filters was accepted")
            assertTrue(response.bodyAsText().contains("\"ok\":false"))
        }
        assertEquals(StreamFilters(), store.settings(1).first().filters)

        val duplicate = saveSort(StreamSort(series = listOf(SortCriterion(SortKey.SIZE), SortCriterion(SortKey.SIZE, SortDirection.ASC))))
        assertEquals(400, duplicate.status.value)
        assertTrue(duplicate.bodyAsText().contains("Size more than once"))
        assertEquals(StreamSort(), store.settings(1).first().sort)
    }

    @Test
    fun `the negate flag and the ignored flags are accepted, zero limits mean unlimited`() = runTest {
        val filters = StreamFilters(
            regex = RegexRules(required = listOf("/remux/in"), excluded = listOf("/cam/gi"), included = listOf("x265")),
            limits = ResultLimits(global = 0, perResolution = 3),
        )
        assertEquals(200, saveFilters(filters).status.value)
        val stored = state(1).filters
        assertEquals(listOf("/remux/in"), stored.regex.required)
        assertEquals(ResultLimits(perResolution = 3), stored.limits)
    }

    private fun profile(id: Long, name: String) =
        ProfileEntity(id = id, name = name, avatarIndex = 0, isKids = false, movieCeiling = null, tvCeiling = null, allowUnrated = true, createdAtMillis = 0)

    /** Every list of every group, every range, limit and deduplicator setting, all non-default. */
    private fun everyFilter(): StreamFilters {
        var filters = StreamFilters()
        FilterAttribute.entries.forEach { attribute ->
            val o = attribute.options ?: listOf("${attribute.name}-a", "${attribute.name}-b", "${attribute.name}-c", "${attribute.name}-d")
            filters = filters.withAttribute(
                attribute,
                AttributeFilter(
                    required = o.take(1),
                    excluded = o.drop(1).take(1),
                    included = o.drop(2).take(1),
                    preferred = o.takeLast(2).reversed(),
                ),
            )
        }
        val resolution = ReleaseRegexTables.RESOLUTION_NAMES.first()
        val gb = 1_000_000_000L
        return filters.copy(
            regex = RegexRules(
                required = listOf("/remux/i"),
                excluded = listOf("/hdcam/in"),
                included = listOf("x265"),
                preferred = listOf(NamedRegex("/atmos/i", "Atmos"), NamedRegex("/dv/i")),
            ),
            seeders = RangeRule(required = ValueRange(min = 5), excluded = ValueRange(max = 1), included = ValueRange(min = 1000)),
            ageHours = RangeRule(required = ValueRange(0, 720), included = ValueRange(max = 24)),
            size = SizeLimits(
                global = MovieSeriesRange(ValueRange(gb, 60 * gb), ValueRange(max = 5 * gb)),
                byResolution = mapOf(resolution to MovieSeriesRange(ValueRange(min = 10 * gb), ValueRange(2 * gb, 20 * gb))),
            ),
            bitrate = SizeLimits(
                global = MovieSeriesRange(ValueRange(min = 2_000_000), ValueRange(max = 40_000_000)),
                byResolution = mapOf(resolution to MovieSeriesRange(series = ValueRange(min = 8_000_000))),
            ),
            limits = ResultLimits(global = 50, perResolution = 10, perQuality = 5, perAddon = 20),
            dedup = DedupSettings(enabled = true, keys = listOf(DedupKey.SMART, DedupKey.URL), mode = DedupMode.SINGLE_RESULT),
        )
    }
}
