package com.seastreams.tv.core.webserver

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.datastore.StreamProcessingStore
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.domain.streams.FormatterId
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

/** Drives the stream filter / formatter endpoints of the real Ktor CIO portal over HTTP, as the phone's browser does. */
class PortalStreamRoutesTest {

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

    @Test
    fun `format-preview renders the aio-formatter template`() = runTest {
        val custom = state(1).presets.getValue(FormatterId.CUSTOM.name)
        val response = post("/api/streams/format-preview", json.encodeToString(FormatPreviewRequest.serializer(), FormatPreviewRequest(custom.name, custom.description)))
        assertEquals(200, response.status.value)
        val preview = json.decodeFromString<FormatPreviewResponse>(response.bodyAsText())
        assertEquals("🔥 4K 🎬 Movie Title (2023)", preview.name)
        assertEquals("🎧 Atmos | TrueHD 🔊 7.1 📦 63 GB ⏱️ 2h:32m:0s 📅 10d", preview.description.lines()[2])
    }

    @Test
    fun `filters typed on the phone are saved for one profile only`() = runTest {
        val filters = state(1).filters.copy(
            keywords = state(1).filters.keywords.copy(excluded = listOf("CAM", "HDTS")),
            regex = state(1).filters.regex.copy(excluded = listOf("/hdcam/in")),
        )
        val body = json.encodeToString(StreamFiltersRequest.serializer(), StreamFiltersRequest(1, filters))
        val saved = post("/api/streams/filters", body)
        assertEquals(200, saved.status.value, saved.bodyAsText())

        assertEquals(listOf("CAM", "HDTS"), store.settings(1).first().filters.keywords.excluded)
        assertEquals(listOf("/hdcam/in"), state(1).filters.regex.excluded)
        assertEquals(emptyList<String>(), state(2).filters.keywords.excluded)
    }

    @Test
    fun `an invalid regex or unknown profile is refused with a readable message`() = runTest {
        val filters = state(1).filters.copy(regex = state(1).filters.regex.copy(required = listOf("/([oops/")))
        val bad = post("/api/streams/filters", json.encodeToString(StreamFiltersRequest.serializer(), StreamFiltersRequest(1, filters)))
        assertEquals(400, bad.status.value)
        assertTrue(json.decodeFromString<ApiMessage>(bad.bodyAsText()).message.startsWith("Not a valid regex"))

        val missing = post("/api/streams/formatter", """{"profileId":9,"formatter":{"id":"PRISM"}}""")
        assertEquals(400, missing.status.value)
        assertEquals(404, browser.get("$base/api/streams?profileId=9").status.value)
    }

    @Test
    fun `format-preview refuses an oversized body before reading it, and a malformed one`() = runTest {
        val huge = post("/api/streams/format-preview", """{"name":"${"x".repeat(MAX_BODY_CHARS)}","description":""}""")
        assertEquals(413, huge.status.value)

        val malformed = post("/api/streams/format-preview", """{"name":1""")
        assertEquals(400, malformed.status.value)
        assertEquals("Malformed request", json.decodeFromString<ApiMessage>(malformed.bodyAsText()).message)
    }

    @Test
    fun `custom templates are saved with the formatter choice`() = runTest {
        val ok = post("/api/streams/formatter", """{"profileId":2,"formatter":{"id":"CUSTOM","customName":"{addon.name}","customDescription":"{stream.filename}"}}""")
        assertEquals(200, ok.status.value)
        val formatter = store.settings(2).first().formatter
        assertEquals(FormatterId.CUSTOM, formatter.id)
        assertEquals("{addon.name}", formatter.customName)
        assertEquals(FormatterId.ORIGINAL, store.settings(1).first().formatter.id)
    }

    private fun profile(id: Long, name: String) =
        ProfileEntity(id = id, name = name, avatarIndex = 0, isKids = false, movieCeiling = null, tvCeiling = null, allowUnrated = true, createdAtMillis = 0)
}
