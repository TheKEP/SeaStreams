package com.seastreams.tv.data.usenet.headers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.testutil.TestHttpServer
import dagger.Lazy
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean

class HeaderVersionUpdaterTest {

    @TempDir
    lateinit var dir: File

    private lateinit var scope: CoroutineScope
    private lateinit var store: UsenetSettingsStore
    private lateinit var github: TestHttpServer
    private val online = AtomicBoolean(true)
    private var now = Instant.ofEpochMilli(1_800_000_000_000)
    private val clock get() = Clock.fixed(now, ZoneOffset.UTC)

    private val tags = mapOf(
        "Prowlarr/Prowlarr" to "v2.8.1.6000",
        "sabnzbd/sabnzbd" to "5.2.0",
        "Sonarr/Sonarr" to "v4.1.0.1",
        "Radarr/Radarr" to "nightly-build",
        "nzbgetcom/nzbget" to "v26.4",
        "theotherp/nzbhydra2" to "v9.4.0",
    )

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = UsenetSettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") })
        github = TestHttpServer {
            get("/repos/{owner}/{repo}/releases/latest") {
                if (!online.get()) return@get call.respondText("down", status = HttpStatusCode.ServiceUnavailable)
                val repo = call.parameters["owner"] + "/" + call.parameters["repo"]
                val tag = tags[repo] ?: return@get call.respondText("{}", status = HttpStatusCode.NotFound)
                call.respondText("""{"tag_name":"$tag","name":"Release"}""")
            }
        }
    }

    @AfterEach
    fun tearDown() {
        github.close()
        scope.cancel()
    }

    private fun updater() = HeaderVersionUpdater(Lazy { OkHttpClient() }, store, Dispatchers.IO, clock, github.baseUrl)

    @Test
    fun `a check stores every plain version, skips odd tags and sends a generic user agent`() = runTest {
        assertTrue(updater().updateNow())

        val headers = store.headers.first()
        assertEquals(
            mapOf(
                HeaderPreset.PROWLARR to "2.8.1.6000",
                HeaderPreset.SABNZBD to "5.2.0",
                HeaderPreset.SONARR to "4.1.0.1",
                HeaderPreset.NZBGET to "26.4",
                HeaderPreset.NZBHYDRA2 to "9.4.0",
            ),
            headers.versions,
        )
        assertEquals(now.toEpochMilli(), headers.checkedAtMillis)
        assertEquals("Radarr 7.0.3.10918", HeaderPreset.RADARR.displayLabel(headers.versions))
        assertEquals("Prowlarr/2.8.1.6000 (alpine 3.24.4)", HeaderPreset.PROWLARR.headers(headers.versions)["User-Agent"])
        assertEquals(6, github.requests.size)
        assertTrue(github.requests.all { it.header("User-Agent") == "SeaStreams" })
    }

    @Test
    fun `the automatic check runs at most once every 24 hours`() = runTest {
        assertTrue(updater().checkIfDue())
        now = now.plusSeconds(23 * 3_600)
        assertFalse(updater().checkIfDue())
        assertEquals(6, github.requests.size)

        now = now.plusSeconds(3_600)
        assertTrue(updater().checkIfDue())
        assertEquals(12, github.requests.size)
    }

    @Test
    fun `Update now ignores the 24 hour gate`() = runTest {
        assertTrue(updater().updateNow())
        assertTrue(updater().updateNow())
        assertEquals(12, github.requests.size)
    }

    @Test
    fun `a failed check keeps the last good versions and is retried next time`() = runTest {
        updater().updateNow()
        val before = store.headers.first()

        online.set(false)
        now = now.plusSeconds(25 * 3_600)
        assertFalse(updater().checkIfDue())
        assertEquals(before, store.headers.first())
        assertFalse(updater().checkIfDue(), "still due, so it tries again")
        assertEquals(18, github.requests.size)
    }

    @Test
    fun `with no successful check the built-in versions are used`() = runTest {
        online.set(false)
        assertFalse(updater().updateNow())
        val headers = store.headers.first()
        assertNull(headers.checkedAtMillis)
        assertEquals("SABnzbd/5.1.3", HeaderPreset.SABNZBD.headers(headers.versions)["User-Agent"])
        assertFalse(updater().updating.value)
    }
}
