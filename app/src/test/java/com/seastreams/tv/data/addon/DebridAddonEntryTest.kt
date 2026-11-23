package com.seastreams.tv.data.addon

import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.datastore.DebridSettingsStore
import com.seastreams.tv.core.database.entity.DebridProviderEntity
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.testutil.FakeDebridProviderDao
import com.seastreams.tv.testutil.InMemoryPreferences
import com.seastreams.tv.ui.screens.settings.streamAddons
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The built-in `Debrid` entry in each profile's addon list (PRD §8, §14). */
class DebridAddonEntryTest : AddonRepositoryTestBase() {

    @Test
    fun `ensureDebrid adds the entry at the bottom of every profile once, keeping a moved position`() = runTest {
        dao.upsert(addon(PROFILE_A, "https://x/manifest.json", 0))
        repository.ensureUsenet()
        repository.ensureDebrid()

        assertEquals(listOf("https://x/manifest.json", BuiltInAddons.USENET_URL, BuiltInAddons.DEBRID_URL), dao.getAll(PROFILE_A).map { it.manifestUrl })
        assertEquals(listOf(BuiltInAddons.USENET_URL, BuiltInAddons.DEBRID_URL), dao.getAll(PROFILE_B).map { it.manifestUrl })

        repository.move(PROFILE_A, BuiltInAddons.DEBRID_URL, -2)
        repository.rename(PROFILE_A, BuiltInAddons.DEBRID_URL, "My cloud")
        repository.ensureDebrid()
        val first = dao.getAll(PROFILE_A).first()
        assertEquals(BuiltInAddons.DEBRID_URL to "My cloud", first.manifestUrl to first.customName)
        assertEquals(3, dao.getAll(PROFILE_A).size)
    }

    @Test
    fun `Manage addons and the portal list the entry only while Debrid is listed`() = runTest {
        dao.upsert(addon(PROFILE_A, "https://x/manifest.json", 0))
        repository.ensureDebrid()
        debridListed.value = false
        assertEquals(listOf("https://x/manifest.json"), repository.observeAddons(PROFILE_A).first().map(AddonEntity::manifestUrl))
        debridListed.value = true
        assertEquals(listOf("https://x/manifest.json", BuiltInAddons.DEBRID_URL), repository.observeAddons(PROFILE_A).first().map(AddonEntity::manifestUrl))
    }

    @Test
    fun `it can't be removed but can be switched off per profile`() = runTest {
        repository.ensureDebrid()
        assertThrows<UserFacingException> { repository.remove(PROFILE_A, BuiltInAddons.DEBRID_URL) }
        repository.setEnabled(PROFILE_A, BuiltInAddons.DEBRID_URL, false)
        assertFalse(dao.getByUrl(PROFILE_A, BuiltInAddons.DEBRID_URL)?.enabled ?: true)
        assertTrue(dao.getByUrl(PROFILE_B, BuiltInAddons.DEBRID_URL)?.enabled ?: false)
        assertTrue(BuiltInAddons.isProtected(BuiltInAddons.DEBRID_URL))
        assertTrue(BuiltInAddons.canDisable(BuiltInAddons.DEBRID_URL))
    }

    @Test
    fun `Debrid can be the preferred auto-play addon while enabled, and is listed only with the toggle on and an enabled provider`() = runTest {
        val debrid = BuiltInAddons.debrid(PROFILE_A, sortOrder = 0, nowMillis = 0)
        val tmdb = BuiltInAddons.tmdb(PROFILE_A, sortOrder = 1, nowMillis = 0)
        assertEquals(listOf(BuiltInAddons.DEBRID_URL), streamAddons(listOf(debrid, tmdb)).map { it.manifestUrl })
        assertEquals(emptyList<String>(), streamAddons(listOf(debrid.copy(enabled = false))).map { it.manifestUrl })
        assertFalse(BuiltInAddons.isListed(BuiltInAddons.DEBRID_URL, usenetConfigured = true, debridListed = false))

        val providers = FakeDebridProviderDao()
        val settings = DebridSettingsStore(InMemoryPreferences())
        val state = DebridAddonState(providers, settings)
        assertFalse(state.listed.first())
        providers.insert(provider(enabled = false))
        settings.setEnabled(true)
        assertFalse(state.listed.first(), "no enabled provider")
        providers.setEnabled(providers.rows.value.single().id, true)
        assertTrue(state.listed.first())
        settings.setEnabled(false)
        assertFalse(state.listed.first(), "Enable Debrid off hides it without touching any row")
        assertEquals(1, providers.rows.value.size)
    }

    private fun provider(enabled: Boolean) = DebridProviderEntity(
        id = 0, providerType = "TORBOX", name = "TorBox", authMethod = "API_KEY", credentialCipher = "enc:x", refreshTokenCipher = null,
        clientIdCipher = null, clientSecretCipher = null, expiresAtMillis = null, signInNeeded = false, enabled = enabled, sortOrder = 0,
    )
}
