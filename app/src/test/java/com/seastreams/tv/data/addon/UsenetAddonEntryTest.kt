package com.seastreams.tv.data.addon

import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.data.UserFacingException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The built-in `Usenet` entry in each profile's addon list (PRD §12–§13). */
class UsenetAddonEntryTest : AddonRepositoryTestBase() {

    @Test
    fun `ensureUsenet adds the entry at the bottom of every profile once, keeping a moved position`() = runTest {
        dao.upsert(addon(PROFILE_A, "https://x/manifest.json", 0))
        dao.upsert(addon(PROFILE_A, "https://y/manifest.json", 1))
        repository.ensureUsenet()

        assertEquals(listOf("https://x/manifest.json", "https://y/manifest.json", BuiltInAddons.USENET_URL), dao.getAll(PROFILE_A).map { it.manifestUrl })
        assertEquals(listOf(BuiltInAddons.USENET_URL), dao.getAll(PROFILE_B).map { it.manifestUrl })

        repository.move(PROFILE_A, BuiltInAddons.USENET_URL, -2)
        repository.ensureUsenet()
        assertEquals(BuiltInAddons.USENET_URL, dao.getAll(PROFILE_A).first().manifestUrl)
        assertEquals(3, dao.getAll(PROFILE_A).size)
    }

    @Test
    fun `the entry is listed only while Usenet is configured`() = runTest {
        dao.upsert(addon(PROFILE_A, "https://x/manifest.json", 0))
        repository.ensureUsenet()
        usenetConfigured.value = false
        assertEquals(listOf("https://x/manifest.json"), repository.observeAddons(PROFILE_A).first().map(AddonEntity::manifestUrl))
        usenetConfigured.value = true
        assertEquals(listOf("https://x/manifest.json", BuiltInAddons.USENET_URL), repository.observeAddons(PROFILE_A).first().map(AddonEntity::manifestUrl))
    }

    @Test
    fun `it can't be removed but can be switched off per profile`() = runTest {
        repository.ensureUsenet()
        assertThrows<UserFacingException> { repository.remove(PROFILE_A, BuiltInAddons.USENET_URL) }
        repository.setEnabled(PROFILE_A, BuiltInAddons.USENET_URL, false)
        assertFalse(dao.getByUrl(PROFILE_A, BuiltInAddons.USENET_URL)?.enabled ?: true)
        assertTrue(dao.getByUrl(PROFILE_B, BuiltInAddons.USENET_URL)?.enabled ?: false)
        assertThrows<UserFacingException> { repository.setEnabled(PROFILE_A, BuiltInAddons.TMDB_URL, false) }
        assertTrue(BuiltInAddons.isProtected(BuiltInAddons.USENET_URL))
    }
}
