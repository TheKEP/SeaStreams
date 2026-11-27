package com.seastreams.tv.data.addon

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.catalog.DiscoverSnapshotStore
import com.seastreams.tv.data.catalog.LoadedCatalogStore
import com.seastreams.tv.data.profile.ProfileDraft
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.testutil.FakeAddonDao
import com.seastreams.tv.testutil.FakeProfileDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** (a) Changes to one profile's addons never reach another; (b) TMDB stays on every profile. */
class PerProfileAddonsTest : AddonRepositoryTestBase() {

    @BeforeEach
    fun seedBoth() = runTest {
        seedDefaults(PROFILE_A)
        seedDefaults(PROFILE_B)
    }

    @Test
    fun `installing on profile A leaves profile B untouched`() = runTest {
        val before = dao.ordered(PROFILE_B)
        repository.install(PROFILE_A, server.url("/good"))

        assertEquals(before, dao.ordered(PROFILE_B))
        assertTrue(server.url("/good/manifest.json") in urls(PROFILE_A))
        assertFalse(server.url("/good/manifest.json") in urls(PROFILE_B))
        assertEquals(dao.ordered(PROFILE_B), repository.observeAddons(PROFILE_B).first())
    }

    @Test
    fun `the same addon can be installed on both profiles independently`() = runTest {
        val url = server.url("/good/manifest.json")
        repository.install(PROFILE_A, url)
        repository.install(PROFILE_B, url)

        repository.remove(PROFILE_A, url)

        assertFalse(url in urls(PROFILE_A))
        assertEquals(listOf(BuiltInAddons.TMDB_URL, BuiltInAddons.OPENSUBTITLES_URL, url), urls(PROFILE_B))
    }

    @Test
    fun `removing, disabling, renaming and reordering on A leave B untouched`() = runTest {
        val before = dao.ordered(PROFILE_B)

        repository.setEnabled(PROFILE_A, BuiltInAddons.OPENSUBTITLES_URL, false)
        repository.rename(PROFILE_A, BuiltInAddons.OPENSUBTITLES_URL, "Subs")
        repository.move(PROFILE_A, BuiltInAddons.OPENSUBTITLES_URL, -1)
        repository.reorder(PROFILE_A, listOf(BuiltInAddons.TMDB_URL, BuiltInAddons.OPENSUBTITLES_URL))
        repository.move(PROFILE_A, BuiltInAddons.OPENSUBTITLES_URL, -1)
        val changed = row(PROFILE_A, BuiltInAddons.OPENSUBTITLES_URL)
        assertEquals(listOf(false, 0, "Subs"), listOf(changed.enabled, changed.sortOrder, changed.customName))

        repository.remove(PROFILE_A, BuiltInAddons.OPENSUBTITLES_URL)

        assertEquals(listOf(BuiltInAddons.TMDB_URL), urls(PROFILE_A))
        assertEquals(before, dao.ordered(PROFILE_B))
    }

    @Test
    fun `built-in TMDB is present on every profile and protected on each`() = runTest {
        listOf(PROFILE_A, PROFILE_B).forEach { profileId ->
            assertTrue(row(profileId, BuiltInAddons.TMDB_URL).enabled)
            assertThrows<UserFacingException> { repository.remove(profileId, BuiltInAddons.TMDB_URL) }
            assertThrows<UserFacingException> { repository.setEnabled(profileId, BuiltInAddons.TMDB_URL, false) }
            assertTrue(row(profileId, BuiltInAddons.TMDB_URL).enabled)
        }
    }

    @Test
    fun `ensureBuiltIns restores TMDB only on profiles that lack it`() = runTest {
        dao.delete(PROFILE_B, BuiltInAddons.TMDB_URL)
        val profileA = dao.ordered(PROFILE_A)

        repository.ensureBuiltIns()

        assertEquals(profileA, dao.ordered(PROFILE_A))
        assertEquals(listOf(BuiltInAddons.OPENSUBTITLES_URL, BuiltInAddons.TMDB_URL), urls(PROFILE_B))
        assertTrue(row(PROFILE_B, BuiltInAddons.TMDB_URL).enabled)
    }
}

/** (d) New profiles get only their own defaults; (e) deleting a profile removes only its addons. */
class ProfileAddonLifecycleTest {

    @TempDir
    lateinit var dir: File

    private lateinit var scope: CoroutineScope
    private lateinit var profileDao: FakeProfileDao
    private lateinit var addonDao: FakeAddonDao
    private lateinit var profiles: ProfileRepository

    @BeforeEach
    fun setUp() = runTest {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = SettingsDataStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") })
        settings.setTmdbApiKey("key")
        profileDao = FakeProfileDao()
        addonDao = FakeAddonDao(profileIds = { profileDao.rows.value.map { it.id } })
        profiles = ProfileRepository(
            profileDao, addonDao, settings, settings, Clock.fixed(Instant.ofEpochMilli(42), ZoneOffset.UTC),
            DiscoverSnapshotStore(), LoadedCatalogStore(Clock.systemUTC()),
        )
    }

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `a new profile receives exactly the default addons, scoped to it alone`() = runTest {
        val first = profiles.create(ProfileDraft(name = "First"))
        addonDao.rename(first.id, BuiltInAddons.OPENSUBTITLES_URL, "Mine")
        addonDao.delete(first.id, BuiltInAddons.OPENSUBTITLES_URL)
        val firstAddons = addonDao.ordered(first.id)

        val second = profiles.create(ProfileDraft(name = "Second"))

        assertEquals(BuiltInAddons.defaults(second.id, 42), addonDao.ordered(second.id))
        assertEquals(firstAddons, addonDao.ordered(first.id))
        assertEquals(listOf(BuiltInAddons.TMDB_URL), addonDao.ordered(first.id).map { it.manifestUrl })
    }

    @Test
    fun `deleting a profile deletes its addons and nobody else's`() = runTest {
        val keep = profiles.create(ProfileDraft(name = "Keep"))
        val gone = profiles.create(ProfileDraft(name = "Gone"))
        val kept = addonDao.ordered(keep.id)

        profiles.delete(gone.id)

        assertTrue(addonDao.ordered(gone.id).isEmpty())
        assertTrue(addonDao.rows.value.values.none { it.profileId == gone.id })
        assertEquals(kept, addonDao.ordered(keep.id))
    }
}
