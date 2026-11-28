package com.seastreams.tv.data.backup

import com.seastreams.tv.core.database.entity.NextUpDismissalEntity
import com.seastreams.tv.data.backup.BackupSamples.backup
import com.seastreams.tv.data.backup.BackupSamples.entry
import com.seastreams.tv.testutil.BackupRig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Milestone 13: the Next Up toggle and removed Next Up cards travel with profile and app backups. */
class NextUpBackupTest {

    private val removed = NextUpDismissalBackup(1399, 1, 4, lastAiredSeason = 1, lastAiredEpisode = 6, dismissedAtMillis = 50)

    @Test
    fun `removed Next Up cards and the toggle round-trip through the codec`() {
        val withNextUp = entry("Ken").let {
            it.copy(
                settings = it.settings.copy(player = it.settings.player.copy(showNextUpInContinueWatching = false)),
                nextUpDismissals = listOf(removed, NextUpDismissalBackup(1400, 2, 1, dismissedAtMillis = 7)),
            )
        }
        val file = backup(BackupScope.PROFILE, withNextUp)

        assertEquals(file, AppBackupCodec.decode(AppBackupCodec.encode(file)))
    }

    @Test
    fun `a file from before Next Up restores no removals and keeps the toggle on`() {
        val legacy = AppBackupCodec.encode(backup(BackupScope.APP, entry("Ken")))
            .replace(Regex(",\\s*\"nextUpDismissals\": \\[\\s*]"), "")
            .replace(Regex(",\\s*\"showNextUpInContinueWatching\": true"), "")
        assertFalse(legacy.contains("nextUpDismissals") || legacy.contains("showNextUpInContinueWatching"), legacy)

        val profile = AppBackupCodec.decode(legacy).profiles.single()

        assertEquals(emptyList<NextUpDismissalBackup>(), profile.nextUpDismissals)
        assertTrue(profile.settings.player.showNextUpInContinueWatching)
    }

    @Test
    fun `invalid removed Next Up cards are refused`() {
        fun with(vararg rows: NextUpDismissalBackup) =
            AppBackupCodec.encode(backup(BackupScope.APP, entry("Ken").copy(nextUpDismissals = rows.toList())))

        assertMessage("Profile 1 (Ken): removed Next Up card 1 has an invalid season or episode") { with(removed.copy(episodeNumber = 0)) }
        assertMessage("Profile 1 (Ken): removed Next Up card 1 has an invalid latest episode") { with(removed.copy(lastAiredEpisode = null)) }
        assertMessage("Profile 1 (Ken) removes the same Next Up show twice") { with(removed, removed.copy(dismissedAtMillis = 9)) }
    }

    @Test
    fun `restore adds missing removals, keeps the newer one per show and carries the toggle`() = runTest {
        val a = BackupRig()
        val ken = a.addProfile("Ken").id
        a.settings.setActiveProfileId(ken)
        a.settings.updatePlayer { it.copy(showNextUpInContinueWatching = false) }
        a.dismissalDao.upsert(NextUpDismissalEntity(ken, 1399, 1, 4, 1, 6, dismissedAtMillis = 50))
        a.dismissalDao.upsert(NextUpDismissalEntity(ken, 1400, 2, 1, null, null, dismissedAtMillis = 10))
        val file = a.exporter.export(BackupScope.PROFILE, ken, includeCredentials = false).json

        val b = BackupRig()
        val target = b.addProfile("Living room").id
        b.dismissalDao.upsert(NextUpDismissalEntity(target, 1400, 2, 3, null, null, dismissedAtMillis = 99))
        b.restorer.restore(file, target)

        assertEquals(
            listOf(NextUpDismissalEntity(target, 1399, 1, 4, 1, 6, 50), NextUpDismissalEntity(target, 1400, 2, 3, null, null, 99)),
            b.dismissalDao.getAll(target).sortedBy { it.tmdbId },
        )
        assertFalse(b.settings.profileSettings(target).player.showNextUpInContinueWatching)
        assertEquals(1, b.profileDao.observeAll().first().size)
    }

    private fun assertMessage(expected: String, encoded: () -> String) {
        val text = encoded()
        val error = assertThrows(InvalidAppBackupException::class.java) { AppBackupCodec.decode(text) }
        assertEquals(expected, error.message)
    }
}
