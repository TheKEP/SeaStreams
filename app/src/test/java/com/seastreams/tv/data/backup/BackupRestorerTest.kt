package com.seastreams.tv.data.backup

import com.seastreams.tv.core.datastore.BackgroundSwatches
import com.seastreams.tv.core.datastore.SubtitleMode
import com.seastreams.tv.core.webserver.PortalBackupController
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.addon.BuiltInAddons
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.MovieCertification
import com.seastreams.tv.testutil.BackupRig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupRestorerTest {

    private val torrentio = "https://torrentio.strem.fun/manifest.json"

    /** A configured TV: Ken (custom addons, settings, stream rules) and a Kids profile, Usenet and Debrid with secrets. */
    private suspend fun source(): BackupRig = BackupRig().apply {
        val ken = addProfile("Ken")
        val kids = addProfile("Kids", kids = true)
        install(ken.id, torrentio, "Torrentio")
        addons.rename(ken.id, torrentio, "My Torrentio")
        addons.setEnabled(ken.id, BuiltInAddons.OPENSUBTITLES_URL, false)
        settings.setActiveProfileId(ken.id)
        settings.updatePlayer { it.copy(autoPlayTrailers = false, trailerAutoPlayDelaySeconds = 5) }
        settings.updateSubtitles { it.copy(mode = SubtitleMode.ALWAYS_ON, preferredLanguage = "pt-BR") }
        settings.setBackgroundColor(BackgroundSwatches.ALL[3])
        streams.updateFormatter(ken.id, enabled = false) { it.copy(customName = "{stream.title}") }
        configureUsenet()
        configureDebrid()
        addons.ensureUsenet()
        addons.ensureDebrid()
        addons.setEnabled(kids.id, BuiltInAddons.USENET_URL, false)
    }

    @Test
    fun `a full backup without credentials carries no secret at all`() = runTest {
        val text = source().exporter.export(BackupScope.APP, null, includeCredentials = false).json

        listOf("nntp-secret", "indexer-key", "rd-access-token", "rd-refresh-token", "rd-client", BackupRig.TMDB_KEY, "enc:").forEach {
            assertFalse(text.contains(it), it)
        }
        listOf("\"password\"", "\"apiKey\"", "\"credential\"", "\"tmdbApiKey\"", "\"clientSecret\"").forEach { assertFalse(text.contains(it), it) }
    }

    @Test
    fun `a full backup with credentials round-trips everything onto an empty TV, re-sealing every secret`() = runTest {
        val a = source()
        val file = a.exporter.export(BackupScope.APP, null, includeCredentials = true)
        assertEquals(BackupFileNames.FULL_BACKUP, file.fileName)
        listOf("nntp-secret", "indexer-key", "rd-access-token", "rd-refresh-token", BackupRig.TMDB_KEY).forEach { assertTrue(file.json.contains(it), it) }

        val b = BackupRig()
        val summary = b.restorer.restore(file.json, targetProfileId = null)

        // Password, API key, the Debrid access/refresh token and client id/secret, and the TMDB key.
        assertEquals(RestoreSummary("app", 0, 2, 1, 1, 1, 7), summary)
        assertEquals(1, b.transactions)
        val aProfiles = a.profileDao.observeAll().first()
        val bProfiles = b.profileDao.observeAll().first()
        assertEquals(aProfiles.map { listOf(it.name, it.isKids, it.movieCeiling, it.tvCeiling) }, bProfiles.map { listOf(it.name, it.isKids, it.movieCeiling, it.tvCeiling) })
        aProfiles.zip(bProfiles).forEach { (pa, pb) ->
            assertEquals(a.addonDao.ordered(pa.id).map { listOf(it.manifestUrl, it.customName, it.enabled, it.sortOrder, it.manifestJson) },
                b.addonDao.ordered(pb.id).map { listOf(it.manifestUrl, it.customName, it.enabled, it.sortOrder, it.manifestJson) })
            assertEquals(a.settings.profileSettings(pa.id), b.settings.profileSettings(pb.id))
            assertEquals(a.streams.settings(pa.id).first(), b.streams.settings(pb.id).first())
        }
        assertEquals(BackupRig.TMDB_KEY, b.settings.currentApiKey())
        assertEquals(bProfiles.first().id, b.settings.activeProfileId.first())
        assertTrue(b.usenetSettings.enabled.first() && b.debridSettings.enabled.first())

        val provider = b.providerDao.getAll().single()
        assertNotEquals("nntp-secret", provider.passwordCipher)
        assertEquals("nntp-secret", b.cipher.decrypt(requireNotNull(provider.passwordCipher)))
        assertEquals("indexer-key", b.cipher.decrypt(requireNotNull(b.indexerDao.getAll().single().apiKeyCipher)))
        val rd = b.debridDao.getAll().single()
        assertEquals("rd-access-token", b.cipher.decrypt(rd.credentialCipher))
        assertEquals("rd-refresh-token", b.cipher.decrypt(requireNotNull(rd.refreshTokenCipher)))
        assertEquals("DEVICE", rd.authMethod)
        assertFalse(rd.signInNeeded)
    }

    @Test
    fun `a backup without credentials keeps the secrets already stored on the TV`() = runTest {
        val text = source().exporter.export(BackupScope.APP, null, includeCredentials = false).json
        val b = BackupRig().apply {
            addProfile("Ken")
            configureUsenet(password = "old-password", apiKey = "old-key")
            configureDebrid(token = "old-token")
        }

        b.restorer.restore(text, null)

        assertEquals(BackupRig.TMDB_KEY, b.settings.currentApiKey())
        assertEquals("old-password", b.cipher.decrypt(requireNotNull(b.providerDao.getAll().single().passwordCipher)))
        assertEquals("old-key", b.cipher.decrypt(requireNotNull(b.indexerDao.getAll().single().apiKeyCipher)))
        assertEquals("old-token", b.cipher.decrypt(b.debridDao.getAll().single().credentialCipher))
        assertEquals(listOf("Ken", "Kids"), b.profileDao.observeAll().first().map { it.name })
    }

    @Test
    fun `sources new to the TV arrive without secrets as Password or Sign-in needed`() = runTest {
        val text = source().exporter.export(BackupScope.APP, null, includeCredentials = false).json
        val b = BackupRig().apply { settings.setTmdbApiKey("b-key") }

        b.restorer.restore(text, null)

        assertNull(b.providerDao.getAll().single().passwordCipher)
        val rd = b.debridDao.getAll().single()
        assertEquals("", rd.credentialCipher)
        assertTrue(rd.signInNeeded)
        assertEquals("b-key", b.settings.currentApiKey())
    }

    @Test
    fun `a profile backup changes only the chosen profile's settings, addons and stream rules - never its fields`() = runTest {
        val a = source()
        val aKids = a.profileDao.observeAll().first().single { it.name == "Kids" }
        val file = a.exporter.export(BackupScope.PROFILE, aKids.id, includeCredentials = false)
        assertEquals("seastreams-profile-kids.json", file.fileName)

        val b = BackupRig()
        val ken = b.addProfile("Ken")
        val other = b.addProfile("Other")
        b.install(other.id, torrentio, "Torrentio")
        b.settings.setActiveProfileId(other.id)
        b.settings.updatePlayer { it.copy(autoPlayNextEpisode = false) }
        b.streams.updateSort(other.id, enabled = false) { it }
        val otherAddons = b.addonDao.ordered(other.id)
        val otherBefore = listOf(b.settings.profileSettings(other.id), b.streams.settings(other.id).first())

        val summary = b.restorer.restore(file.json, targetProfileId = ken.id)

        assertEquals(RestoreSummary("profile", 1, 0, 1, 1, 1, 0), summary)
        val kenAfter = requireNotNull(b.profileDao.getById(ken.id))
        assertEquals("Ken", kenAfter.name)
        assertFalse(kenAfter.isKids)
        assertEquals(a.addonUrls(aKids.id), b.addonUrls(ken.id))
        assertEquals(a.settings.profileSettings(aKids.id), b.settings.profileSettings(ken.id))
        assertEquals(otherBefore, listOf(b.settings.profileSettings(other.id), b.streams.settings(other.id).first()))
        // Its own list is untouched; the restored sources being configured now only appends their built-in entries.
        assertEquals(otherAddons, b.addonDao.ordered(other.id).take(otherAddons.size))
        assertEquals(BackupRig.BUILT_IN_SOURCES, b.addonUrls(other.id).drop(otherAddons.size))
        assertEquals(other.id, b.settings.activeProfileId.first())
    }

    @Test
    fun `an app restore updates same-named profiles, including their Kids limits`() = runTest {
        val text = source().exporter.export(BackupScope.APP, null, includeCredentials = false).json
        val b = BackupRig()
        val kids = b.addProfile("KIDS")

        val summary = b.restorer.restore(text, null)

        assertEquals(1, summary.profilesUpdated)
        assertEquals(1, summary.profilesCreated)
        val updated = requireNotNull(b.profileDao.getById(kids.id))
        assertEquals("Kids", updated.name)
        assertTrue(updated.isKids)
        assertEquals(MovieCertification.PG, updated.movieCeiling)
    }

    @Test
    fun `a refused restore writes nothing`() = runTest {
        val text = source().exporter.export(BackupScope.APP, null, includeCredentials = true).json
        val b = BackupRig()
        listOf("A", "B", "C", "D").forEach { b.addProfile(it) }
        val before = listOf(b.profileDao.rows.value, b.addonDao.rows.value, b.prefs.keys, b.providerDao.getAll(), b.debridDao.getAll())

        assertRefused<UserFacingException> { b.restorer.restore(text, null) }
        assertRefused<InvalidAppBackupException> { b.restorer.restore("{ nope", null) }
        assertRefused<InvalidAppBackupException> { b.restorer.restore(text.replaceFirst("\"version\": 1", "\"version\": 7"), null) }

        assertEquals(0, b.transactions)
        assertEquals(before, listOf(b.profileDao.rows.value, b.addonDao.rows.value, b.prefs.keys, b.providerDao.getAll(), b.debridDao.getAll()))
    }

    @Test
    fun `Library and watch progress round-trip onto an empty TV, Continue Watching and watched state included`() = runTest {
        val a = BackupRig()
        val ken = a.addProfile("Ken")
        a.save(ken.id, 550, "Fight Club", addedAt = 1_000, certification = "R")
        a.save(ken.id, 603, "The Matrix", addedAt = 2_000, certification = "R")
        a.watch(ken.id, 550, positionMs = 30_000, updatedAt = 10)
        a.watch(ken.id, 603, positionMs = 95_000, updatedAt = 11)
        a.watch(ken.id, 1399, positionMs = 50_000, updatedAt = 12, season = 1, episode = 3, dismissed = true)
        val text = a.exporter.export(BackupScope.APP, null, includeCredentials = false).json

        val b = BackupRig().apply { settings.setTmdbApiKey("b-key") }
        val summary = b.restorer.restore(text, null)

        val bKen = b.profileDao.observeAll().first().single().id
        assertEquals(a.libraryDao.getAll(ken.id).map { it.copy(profileId = 0) }.sortedBy { it.tmdbId }, b.libraryDao.getAll(bKen).map { it.copy(profileId = 0) }.sortedBy { it.tmdbId })
        assertEquals(a.progressDao.getAll(ken.id).map { it.copy(profileId = 0) }, b.progressDao.getAll(bKen).map { it.copy(profileId = 0) })
        assertEquals(listOf(550 to 30_000L), b.progressDao.observeContinueWatching(bKen).first().map { it.tmdbId to it.positionMs })
        assertEquals(listOf(603), b.progressDao.observeCompletedIds(bKen, MediaType.MOVIE).first())
        assertTrue(b.progressDao.getAll(bKen).single { it.tmdbId == 1399 }.dismissedFromContinueWatching)
        assertEquals(2, summary.libraryItems)
        assertEquals(3, summary.watchProgress)
        assertTrue(PortalBackupController.message(summary).contains("Added 2 library titles and 3 watch progress entries"))

        val again = b.restorer.restore(text, null)
        assertEquals(0 to 0, again.libraryItems to again.watchProgress)
    }

    @Test
    fun `restoring onto a TV in use keeps saved dates and never rewinds newer progress`() = runTest {
        val a = BackupRig()
        val ken = a.addProfile("Ken")
        a.save(ken.id, 550, "Fight Club", addedAt = 5_000)
        a.save(ken.id, 603, "The Matrix", addedAt = 6_000)
        a.watch(ken.id, 550, positionMs = 20_000, updatedAt = 100)
        a.watch(ken.id, 603, positionMs = 80_000, updatedAt = 300)
        a.watch(ken.id, 13, positionMs = 60_000, updatedAt = 200)
        a.watch(ken.id, 24, positionMs = 10_000, updatedAt = 50)
        val file = a.exporter.export(BackupScope.PROFILE, ken.id, includeCredentials = false).json

        val b = BackupRig()
        val target = b.addProfile("Living room").id
        b.save(target, 550, "Fight Club", addedAt = 1_000)
        b.watch(target, 550, positionMs = 90_000, updatedAt = 200)
        b.watch(target, 603, positionMs = 10_000, updatedAt = 100)
        b.watch(target, 13, positionMs = 30_000, updatedAt = 200)

        val summary = b.restorer.restore(file, target)

        assertEquals(mapOf(550 to 1_000L, 603 to 6_000L), b.libraryDao.getAll(target).associate { it.tmdbId to it.addedAtMillis })
        val positions = b.progressDao.getAll(target).associate { it.tmdbId to it.positionMs }
        // Newer on the TV: kept. Newer in the backup: restored. Same time: the further-watched one. Missing: added.
        assertEquals(mapOf(550 to 90_000L, 603 to 80_000L, 13 to 60_000L, 24 to 10_000L), positions)
        assertEquals(1, summary.libraryItems)
        assertEquals(3, summary.watchProgress)
    }

    @Test
    fun `a Kids profile only receives Library titles within its limits`() = runTest {
        val a = BackupRig()
        val ken = a.addProfile("Ken")
        a.save(ken.id, 550, "Fight Club", addedAt = 1, certification = "R")
        a.save(ken.id, 862, "Toy Story", addedAt = 2, certification = "G")
        a.save(ken.id, 100, "Unrated short", addedAt = 3, certification = null)
        val file = a.exporter.export(BackupScope.PROFILE, ken.id, includeCredentials = false).json

        val b = BackupRig()
        val kids = b.addProfile("Kids", kids = true).id
        val summary = b.restorer.restore(file, kids)

        assertEquals(listOf(862), b.libraryDao.getAll(kids).map { it.tmdbId })
        assertEquals(1, summary.libraryItems)
    }

    private suspend inline fun <reified E : Throwable> assertRefused(crossinline block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue(error is E, "expected ${E::class.simpleName}, got $error")
    }
}
