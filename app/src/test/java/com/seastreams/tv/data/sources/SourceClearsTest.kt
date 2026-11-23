package com.seastreams.tv.data.sources

import com.seastreams.tv.core.datastore.DebridSettingsStore
import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.data.debrid.DebridServiceKind
import com.seastreams.tv.data.debrid.DebridSourcesRepository
import com.seastreams.tv.data.debrid.auth.DeviceAuthSessions
import com.seastreams.tv.data.debrid.auth.DeviceAuthStatus
import com.seastreams.tv.data.debrid.auth.DeviceFlowProvider
import com.seastreams.tv.data.debrid.auth.DevicePoll
import com.seastreams.tv.data.debrid.auth.DeviceTokens
import com.seastreams.tv.data.usenet.IndexerDraft
import com.seastreams.tv.data.usenet.ProviderDraft
import com.seastreams.tv.data.usenet.UsenetSourcesRepository
import com.seastreams.tv.data.usenet.headers.HeaderPreset
import com.seastreams.tv.data.usenet.headers.HeaderUse
import com.seastreams.tv.domain.usenet.UsenetProfile
import com.seastreams.tv.testutil.FakeDebridProviderDao
import com.seastreams.tv.testutil.FakeNewznabIndexerDao
import com.seastreams.tv.testutil.FakeSecretCipher
import com.seastreams.tv.testutil.FakeUsenetProviderDao
import com.seastreams.tv.testutil.InMemoryPreferences
import com.seastreams.tv.testutil.ScriptedDeviceFlow
import com.seastreams.tv.testutil.VirtualClock
import com.seastreams.tv.usenet.nntp.DefaultSocketOpener
import com.seastreams.tv.usenet.nntp.NntpEngine
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `Clear Usenet settings` and `Clear Debrid settings` (PRD §11 `Sources`): each deletes only its own source, only
 * after its own 5-second Undo window, and the two windows run independently. Both stores share one DataStore, as on
 * the device.
 */
class SourceClearsTest {

    private val cipher = FakeSecretCipher()
    private val prefs = InMemoryPreferences()
    private val usenetStore = UsenetSettingsStore(prefs)
    private val debridStore = DebridSettingsStore(prefs)
    private val providerDao = FakeUsenetProviderDao()
    private val indexerDao = FakeNewznabIndexerDao()
    private val debridDao = FakeDebridProviderDao()
    private val usenet = UsenetSourcesRepository(providerDao, indexerDao, cipher, Dispatchers.Unconfined)
    private val debrid = DebridSourcesRepository(debridDao, cipher, Dispatchers.Unconfined)
    private val engine = NntpEngine(DefaultSocketOpener, { 0L }, null)
    private val torBox = ScriptedDeviceFlow(DebridServiceKind.TORBOX, ArrayDeque(listOf(DevicePoll.Pending, DevicePoll.Linked(DeviceTokens("late")))))

    private class Clears(val usenet: UsenetSettingsClear, val debrid: DebridSettingsClear, val sessions: DeviceAuthSessions)

    private fun TestScope.clears(): Clears {
        val clock = VirtualClock(testScheduler)
        val sessions = DeviceAuthSessions(DeviceFlowProvider { if (it == torBox.kind) torBox else null }, debrid, backgroundScope, clock)
        return Clears(
            UsenetSettingsClear(usenet, usenetStore, Lazy { engine }, backgroundScope, clock, Dispatchers.Unconfined),
            DebridSettingsClear(debrid, debridStore, sessions, backgroundScope, clock),
            sessions,
        )
    }

    private suspend fun configureEverything() {
        usenet.saveProvider(null, ProviderDraft("Eweka", "news.eweka.nl", username = "me", password = "hunter2"))
        usenet.saveIndexer(null, IndexerDraft("NZBgeek", "https://api.nzbgeek.info", apiKey = "geek-key"))
        usenetStore.setEnabled(true)
        usenetStore.updatePerformance { it.withProfile(UsenetProfile.HIGH) }
        usenetStore.setHeaderPreset(HeaderUse.SEARCH, HeaderPreset.SONARR)
        usenetStore.finishToggleMigration(enable = true)
        debrid.saveKey(null, DebridServiceKind.REAL_DEBRID, "rd-key-0123456789", null)
        debrid.saveKey(null, DebridServiceKind.PREMIUMIZE, "pm-key-0123456789", null)
        debridStore.setEnabled(true)
    }

    private suspend fun assertUsenetIntact() {
        assertEquals(1, providerDao.rows.value.size)
        assertEquals(1, indexerDao.rows.value.size)
        assertTrue(usenetStore.enabled.first())
        assertEquals(UsenetProfile.HIGH, usenetStore.performance.first().profile)
        assertEquals(HeaderPreset.SONARR, usenetStore.headers.first().search)
    }

    private suspend fun assertDebridIntact() {
        assertEquals(listOf("REAL_DEBRID", "PREMIUMIZE"), debridDao.rows.value.map { it.providerType })
        assertEquals("rd-key-0123456789", debrid.credentials(debridDao.rows.value.first().id).accessToken)
        assertTrue(debridStore.enabled.first())
    }

    private fun TestScope.tick(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun `Clear Usenet settings empties only Usenet, after 5 seconds`() = runTest {
        configureEverything()
        val clears = clears()

        clears.usenet.schedule()
        assertEquals(5_000L, clears.usenet.deadline.value)
        tick(4_999)
        assertUsenetIntact()

        tick(2)
        assertNull(clears.usenet.deadline.value)
        assertTrue(providerDao.rows.value.isEmpty())
        assertTrue(indexerDao.rows.value.isEmpty())
        assertFalse(usenetStore.enabled.first(), "Enable Usenet reads OFF again")
        assertEquals(UsenetProfile.CONSERVATIVE, usenetStore.performance.first().profile)
        assertEquals(HeaderPreset.DEFAULT_SEARCH, usenetStore.headers.first().search)
        assertEquals(setOf("usenet/enabled_migrated", "debrid/enabled"), prefs.keys, "only Usenet keys went (the upgrade marker stays)")
        assertDebridIntact()
    }

    @Test
    fun `Clear Debrid settings empties only Debrid, after 5 seconds, and stops pending sign-ins`() = runTest {
        configureEverything()
        val clears = clears()
        val signIn = clears.sessions.start(DebridServiceKind.TORBOX)

        clears.debrid.schedule()
        tick(4_999)
        assertDebridIntact()
        tick(2)

        assertTrue(debridDao.rows.value.isEmpty())
        assertFalse(debridStore.enabled.first(), "Enable Debrid reads OFF again")
        assertFalse(prefs.keys.any { it.startsWith("debrid/") })
        assertEquals(DeviceAuthStatus.CANCELLED, clears.sessions.status(signIn.id)?.status)
        tick(20_000)
        assertTrue(debridDao.rows.value.isEmpty(), "a code completing after the clear is discarded")
        assertUsenetIntact()
    }

    @Test
    fun `Undo inside the window keeps everything, for either source`() = runTest {
        configureEverything()
        val clears = clears()

        clears.usenet.schedule()
        clears.debrid.schedule()
        tick(3_000)
        assertTrue(clears.usenet.undo())
        assertTrue(clears.debrid.undo())
        assertFalse(clears.usenet.undo(), "nothing left to undo")
        tick(10_000)

        assertNull(clears.usenet.deadline.value)
        assertNull(clears.debrid.deadline.value)
        assertUsenetIntact()
        assertDebridIntact()
    }

    @Test
    fun `both windows run independently`() = runTest {
        configureEverything()
        val clears = clears()

        clears.usenet.schedule()
        tick(2_000)
        clears.debrid.schedule()
        assertEquals(5_000L to 7_000L, clears.usenet.deadline.value to clears.debrid.deadline.value)
        clears.usenet.undo()
        tick(5_001)

        assertUsenetIntact()
        assertTrue(debridDao.rows.value.isEmpty())

        clears.usenet.schedule()
        tick(5_001)
        assertTrue(providerDao.rows.value.isEmpty())
    }

    @Test
    fun `a second press keeps the first deadline`() = runTest {
        configureEverything()
        val clears = clears()
        clears.debrid.schedule()
        tick(3_000)
        clears.debrid.schedule()
        assertEquals(5_000L, clears.debrid.deadline.value)
        tick(2_001)
        assertTrue(debridDao.rows.value.isEmpty())
    }
}
