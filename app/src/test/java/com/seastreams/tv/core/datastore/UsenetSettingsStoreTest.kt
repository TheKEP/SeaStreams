package com.seastreams.tv.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.seastreams.tv.data.usenet.headers.HeaderPreset
import com.seastreams.tv.data.usenet.headers.HeaderUse
import com.seastreams.tv.domain.usenet.UsenetPerformance
import com.seastreams.tv.domain.usenet.UsenetProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class UsenetSettingsStoreTest {

    @TempDir
    lateinit var dir: File

    private lateinit var scope: CoroutineScope
    private val dataStore by lazy { PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") } }
    private val store by lazy { UsenetSettingsStore(dataStore) }

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `nothing stored reads as Conservative with Prowlarr and SABnzbd headers`() = runTest {
        assertEquals(UsenetPerformance(), store.performance.first())
        assertEquals(UsenetProfile.CONSERVATIVE, store.performance.first().profile)
        val headers = store.headers.first()
        assertEquals(HeaderPreset.PROWLARR, headers.search)
        assertEquals(HeaderPreset.SABNZBD, headers.download)
        assertEquals(emptyMap<HeaderPreset, String>(), headers.versions)
        assertNull(headers.checkedAtMillis)
    }

    @Test
    fun `performance edits persist across store instances and are app-wide keys`() = runTest {
        store.updatePerformance { it.withProfile(UsenetProfile.HIGH) }
        store.updatePerformance { it.edited { p -> p.copy(readAheadSegments = 999) } }

        val reread = UsenetSettingsStore(dataStore).performance.first()
        assertEquals(UsenetProfile.CUSTOM, reread.profile)
        assertEquals(256, reread.readAheadSegments)
        assertEquals(2_048, reread.segmentDiskCacheMb)
        val keys = dataStore.data.first().asMap().keys.map { it.name }
        assertEquals(listOf("usenet/performance"), keys)
    }

    @Test
    fun `unreadable performance JSON falls back to the defaults`() = runTest {
        dataStore.edit { it[stringPreferencesKey("usenet/performance")] = "{not json" }
        assertEquals(UsenetPerformance(), store.performance.first())
        dataStore.edit { it[stringPreferencesKey("usenet/performance")] = """{"profile":"NOPE"}""" }
        assertEquals(UsenetPerformance(), store.performance.first())
        assertEquals(UsenetProfile.CONSERVATIVE, store.performance.first().profile, "unreadable JSON falls back to Conservative")
    }

    @Test
    fun `header choices and fetched versions persist, and bad stored versions are dropped`() = runTest {
        store.setHeaderPreset(HeaderUse.SEARCH, HeaderPreset.NZBHYDRA2)
        store.setHeaderPreset(HeaderUse.DOWNLOAD, HeaderPreset.NZBGET)
        store.saveHeaderVersions(mapOf(HeaderPreset.PROWLARR to "2.8.0.1"), checkedAtMillis = 10)
        store.saveHeaderVersions(mapOf(HeaderPreset.SABNZBD to "5.2.0"), checkedAtMillis = 20)

        val headers = store.headers.first()
        assertEquals(HeaderPreset.NZBHYDRA2, headers.search)
        assertEquals(HeaderPreset.NZBGET, headers.download)
        assertEquals(mapOf(HeaderPreset.PROWLARR to "2.8.0.1", HeaderPreset.SABNZBD to "5.2.0"), headers.versions)
        assertEquals(20L, headers.checkedAtMillis)

        dataStore.edit { it[stringPreferencesKey("usenet/header_versions")] = """{"PROWLARR":"2.9 (evil)","GONE":"1.0","NZBGET":"26.4"}""" }
        assertEquals(mapOf(HeaderPreset.NZBGET to "26.4"), store.headers.first().versions)
    }
}
