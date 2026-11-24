package com.seastreams.tv.data.backup

import com.seastreams.tv.testutil.BackupRig
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupExporterTest {

    @Test
    fun `each profile exports its own Library, oldest first, and every watch progress record`() = runTest {
        val tv = BackupRig()
        val ken = tv.addProfile("Ken")
        val kids = tv.addProfile("Kids", kids = true)
        // An MDBList import keeps the time it was imported; a later manual save comes after it.
        tv.save(ken.id, 603, "The Matrix", addedAt = 2_000, certification = "R")
        tv.save(ken.id, 550, "Fight Club", addedAt = 1_000, certification = "R")
        tv.watch(ken.id, 603, positionMs = 40_000, updatedAt = 10)
        tv.watch(ken.id, 1399, positionMs = 99_000, updatedAt = 11, season = 1, episode = 2)
        tv.watch(ken.id, 550, positionMs = 12_000, updatedAt = 12, dismissed = true)
        tv.save(kids.id, 862, "Toy Story", addedAt = 3_000, certification = "G")

        val profile = AppBackupCodec.decode(tv.exporter.export(BackupScope.PROFILE, ken.id, includeCredentials = false).json).profiles.single()

        assertEquals(listOf(550, 603), profile.library.map { it.tmdbId })
        assertEquals(LibraryItemBackup(550, "MOVIE", "Fight Club", "/p550.jpg", "R", "28,12", 1_000), profile.library.first())
        assertEquals(listOf(603, 1399, 550), profile.watchProgress.map { it.tmdbId })
        val episode = profile.watchProgress[1]
        assertEquals(listOf("TV", 1, 2, "Episode 2", "/b1399.jpg", 99_000L, 100_000L), listOf(episode.mediaType, episode.seasonNumber, episode.episodeNumber, episode.episodeTitle, episode.backdropPath, episode.positionMs, episode.durationMs))
        assertTrue(profile.watchProgress.last().dismissedFromContinueWatching)

        val full = AppBackupCodec.decode(tv.exporter.export(BackupScope.APP, null, includeCredentials = false).json).profiles
        assertEquals(listOf(listOf(550, 603), listOf(862)), full.map { p -> p.library.map { it.tmdbId } })
        assertEquals(listOf(3, 0), full.map { it.watchProgress.size })
    }
}
