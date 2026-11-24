package com.seastreams.tv.data.backup

import com.seastreams.tv.data.addon.BuiltInAddons
import com.seastreams.tv.testutil.BackupRig
import com.seastreams.tv.testutil.BackupRig.Companion.BUILT_IN_SOURCES
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A backup taken before Usenet or Debrid was set up has no `usenet://builtin` / `debrid://builtin` row in its addon
 * lists. Restoring it must still leave every profile with those entries once the sources are configured, straight
 * away, not only after the next app start (when `BuiltInAddonGuard`'s background upkeep would notice).
 */
class BackupBuiltInEntriesTest {

    private val torrentio = "https://torrentio.strem.fun/manifest.json"

    /** A TV whose sources are configured but whose addon lists never got the built-in rows (the guard hadn't run). */
    private suspend fun sourceWithoutBuiltInRows(): BackupRig = BackupRig().apply {
        val ken = addProfile("Ken")
        addProfile("Kids", kids = true)
        install(ken.id, torrentio, "Torrentio")
        configureUsenet()
        configureDebrid()
    }

    @Test
    fun `restoring onto an empty TV appends both entries to every restored profile, new ones included`() = runTest {
        val a = sourceWithoutBuiltInRows()
        val text = a.exporter.export(BackupScope.APP, null, includeCredentials = true).json
        assertFalse(text.contains(BuiltInAddons.USENET_URL) || text.contains(BuiltInAddons.DEBRID_URL))

        val b = BackupRig()
        b.restorer.restore(text, null)

        val profiles = b.profileDao.observeAll().first()
        assertEquals(2, profiles.size)
        profiles.forEach { p ->
            val urls = b.addonUrls(p.id)
            assertEquals(BUILT_IN_SOURCES, urls.takeLast(2), p.name)
            assertTrue(BUILT_IN_SOURCES.all { b.addon(p.id, it)?.enabled == true }, p.name)
            assertEquals(urls.indices.toList(), b.addonDao.ordered(p.id).map { it.sortOrder }, "dense order for ${p.name}")
        }
        assertEquals(listOf(BuiltInAddons.TMDB_URL, BuiltInAddons.OPENSUBTITLES_URL, torrentio) + BUILT_IN_SOURCES, b.addonUrls(profiles.first().id))
    }

    @Test
    fun `an existing profile whose list is replaced while the sources stay on gets the entries back`() = runTest {
        val a = sourceWithoutBuiltInRows()
        val aKen = a.profileDao.observeAll().first().first()
        val profileFile = a.exporter.export(BackupScope.PROFILE, aKen.id, includeCredentials = false).json

        // Already configured on the TV, so the guard's (shown, profile ids) key won't change during the restore.
        val b = BackupRig().apply {
            configureUsenet()
            configureDebrid()
        }
        val ken = b.addProfile("Ken")
        b.addons.ensureUsenet()
        b.addons.ensureDebrid()
        b.addonDao.delete(ken.id, BuiltInAddons.USENET_URL)
        b.addonDao.delete(ken.id, BuiltInAddons.DEBRID_URL)

        b.restorer.restore(profileFile, targetProfileId = ken.id)

        assertEquals(listOf(BuiltInAddons.TMDB_URL, BuiltInAddons.OPENSUBTITLES_URL, torrentio) + BUILT_IN_SOURCES, b.addonUrls(ken.id))
    }

    @Test
    fun `a built-in entry the backup doesn't list keeps the profile's own name and off state, without duplicates`() = runTest {
        val a = sourceWithoutBuiltInRows()
        val aKen = a.profileDao.observeAll().first().first()
        val profileFile = a.exporter.export(BackupScope.PROFILE, aKen.id, includeCredentials = false).json

        val b = BackupRig().apply { configureUsenet() }
        val ken = b.addProfile("Ken")
        b.addons.ensureUsenet()
        b.addons.rename(ken.id, BuiltInAddons.USENET_URL, "My NZBs")
        b.addons.setEnabled(ken.id, BuiltInAddons.USENET_URL, false)
        b.addons.move(ken.id, BuiltInAddons.USENET_URL, -10)

        b.restorer.restore(profileFile, targetProfileId = ken.id)

        val urls = b.addonUrls(ken.id)
        assertEquals(1, urls.count { it == BuiltInAddons.USENET_URL })
        assertEquals(1, urls.count { it == BuiltInAddons.DEBRID_URL })
        val usenet = requireNotNull(b.addon(ken.id, BuiltInAddons.USENET_URL))
        assertEquals("My NZBs", usenet.customName)
        assertFalse(usenet.enabled)
        // Kept rows go to the bottom, after the backup's list; the newly configured Debrid entry is appended after it.
        assertEquals(listOf(BuiltInAddons.TMDB_URL, BuiltInAddons.OPENSUBTITLES_URL, torrentio) + BUILT_IN_SOURCES, urls)
    }

    @Test
    fun `the guard's refresh adds nothing while the sources are off and nothing twice`() = runTest {
        val b = BackupRig()
        val ken = b.addProfile("Ken")
        b.guard.refreshSourceEntries()
        assertEquals(listOf(BuiltInAddons.TMDB_URL, BuiltInAddons.OPENSUBTITLES_URL), b.addonUrls(ken.id))

        b.configureUsenet()
        b.configureDebrid()
        b.guard.refreshSourceEntries()
        b.guard.refreshSourceEntries()
        assertEquals(listOf(BuiltInAddons.TMDB_URL, BuiltInAddons.OPENSUBTITLES_URL) + BUILT_IN_SOURCES, b.addonUrls(ken.id))
    }
}
