package com.seastreams.tv.data.setup

import com.seastreams.tv.data.catalog.DiscoverSnapshotStore
import com.seastreams.tv.data.catalog.LoadedCatalogStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.data.profile.ProfileDraft
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.testutil.FakeProfileDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DebugProfileSeederTest {

    @TempDir
    lateinit var dir: File

    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsDataStore
    private lateinit var profileDao: FakeProfileDao
    private lateinit var profiles: ProfileRepository

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsDataStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") })
        profileDao = FakeProfileDao()
        profiles = ProfileRepository(profileDao, settings, settings, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), DiscoverSnapshotStore(), LoadedCatalogStore(Clock.systemUTC()))
    }

    @AfterEach
    fun tearDown() = scope.cancel()

    private fun seeder(enabled: Boolean = true, key: String = DEV_KEY) =
        DebugProfileSeeder(DebugSeedConfig(enabled, key), settings, profiles)

    @Test
    fun `a fresh debug install gets the Test profile and the developer key`() = runTest {
        assertTrue(seeder().seedIfNeeded())

        assertEquals(DEV_KEY, settings.currentApiKey())
        assertEquals(listOf(DebugProfileSeeder.PROFILE_NAME), profileDao.rows.value.map { it.name })
        assertFalse(profileDao.rows.value.single().isKids)
    }

    @Test
    fun `nothing is seeded once a profile exists`() = runTest {
        settings.setTmdbApiKey("user-key")
        profiles.create(ProfileDraft(name = "Ken"))

        assertFalse(seeder().seedIfNeeded())
        assertEquals(listOf("Ken"), profileDao.rows.value.map { it.name })
        assertEquals("user-key", settings.currentApiKey())
    }

    @Test
    fun `a second launch does not add another Test profile`() = runTest {
        seeder().seedIfNeeded()

        assertFalse(seeder().seedIfNeeded())
        assertEquals(1, profileDao.rows.value.size)
    }

    @Test
    fun `an already saved key is kept`() = runTest {
        settings.setTmdbApiKey("user-key")

        assertTrue(seeder().seedIfNeeded())
        assertEquals("user-key", settings.currentApiKey())
    }

    @Test
    fun `release builds seed nothing`() = runTest {
        assertFalse(seeder(enabled = false).seedIfNeeded())

        assertNull(settings.currentApiKey())
        assertTrue(profileDao.rows.value.isEmpty())
    }

    @Test
    fun `a missing local properties key seeds nothing`() = runTest {
        assertFalse(seeder(key = "  ").seedIfNeeded())

        assertNull(settings.currentApiKey())
        assertTrue(profileDao.rows.value.isEmpty())
    }

    private companion object {
        const val DEV_KEY = "0123456789abcdef0123456789abcdef"
    }
}
