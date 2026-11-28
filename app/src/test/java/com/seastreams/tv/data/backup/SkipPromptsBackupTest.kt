package com.seastreams.tv.data.backup

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.seastreams.tv.core.datastore.PlayerSettings
import com.seastreams.tv.core.datastore.ProfileSettingsReader
import com.seastreams.tv.core.datastore.ProfileSettingsWriter
import com.seastreams.tv.core.datastore.SkipPromptMode
import com.seastreams.tv.data.backup.BackupSamples.backup
import com.seastreams.tv.data.backup.BackupSamples.entry
import com.seastreams.tv.testutil.BackupRig
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** Milestone 14: the skip / Next episode prompt settings are per profile and travel with backups. */
class SkipPromptsBackupTest {

    private val custom = PlayerSettings(
        skipIntroRecap = SkipPromptMode.AUTO,
        skipCredits = SkipPromptMode.OFF,
        nextEpisodePrompt = false,
        onlineSkipTimes = false,
    )

    @Test
    fun `defaults are button, button, on, on`() {
        val defaults = ProfileSettingsReader(mutablePreferencesOf(), 7).player()

        assertEquals(SkipPromptMode.BUTTON, defaults.skipIntroRecap)
        assertEquals(SkipPromptMode.BUTTON, defaults.skipCredits)
        assertEquals(true, defaults.nextEpisodePrompt)
        assertEquals(true, defaults.onlineSkipTimes)
    }

    @Test
    fun `settings are stored under the profile's own p{id} keys`() {
        val prefs = mutablePreferencesOf()
        ProfileSettingsWriter(prefs, 7).player(custom)

        assertEquals("AUTO", prefs[stringPreferencesKey("p7/player_skip_intro_recap")])
        assertEquals("OFF", prefs[stringPreferencesKey("p7/player_skip_credits")])
        assertEquals(false, prefs[booleanPreferencesKey("p7/player_next_episode_prompt")])
        assertEquals(false, prefs[booleanPreferencesKey("p7/player_skip_online_lookup")])
        assertEquals(custom.skipIntroRecap, ProfileSettingsReader(prefs, 7).player().skipIntroRecap)
        // Another profile still sees the defaults.
        assertEquals(SkipPromptMode.BUTTON, ProfileSettingsReader(prefs, 8).player().skipIntroRecap)
    }

    @Test
    fun `an unknown stored mode reads as the default`() {
        val prefs = mutablePreferencesOf(stringPreferencesKey("p7/player_skip_credits") to "SOMETIMES")

        assertEquals(SkipPromptMode.BUTTON, ProfileSettingsReader(prefs, 7).player().skipCredits)
    }

    @Test
    fun `the settings round-trip through the backup codec`() {
        val withSkip = entry("Ken").let { it.copy(settings = it.settings.copy(player = PlayerBackup.of(custom))) }
        val file = backup(BackupScope.PROFILE, withSkip)
        val decoded = AppBackupCodec.decode(AppBackupCodec.encode(file))

        assertEquals(file, decoded)
        val restored = decoded.profiles.single().settings.toAppSettings().player
        assertEquals(SkipPromptMode.AUTO, restored.skipIntroRecap)
        assertEquals(SkipPromptMode.OFF, restored.skipCredits)
        assertFalse(restored.nextEpisodePrompt)
        assertFalse(restored.onlineSkipTimes)
    }

    @Test
    fun `a file from before the prompts restores the defaults`() {
        val legacy = AppBackupCodec.encode(backup(BackupScope.APP, entry("Ken")))
            .replace(Regex(",\\s*\"skipIntroRecap\": \"BUTTON\""), "")
            .replace(Regex(",\\s*\"skipCredits\": \"BUTTON\""), "")
            .replace(Regex(",\\s*\"nextEpisodePrompt\": true"), "")
            .replace(Regex(",\\s*\"onlineSkipTimes\": true"), "")
        assertFalse(legacy.contains("skipIntroRecap") || legacy.contains("onlineSkipTimes"), legacy)

        val player = AppBackupCodec.decode(legacy).profiles.single().settings.toAppSettings().player

        assertEquals(PlayerSettings().skipIntroRecap, player.skipIntroRecap)
        assertEquals(PlayerSettings().skipCredits, player.skipCredits)
        assertEquals(true, player.nextEpisodePrompt)
        assertEquals(true, player.onlineSkipTimes)
    }

    @Test
    fun `export then restore carries the settings to the target profile`() = runTest {
        val a = BackupRig()
        val ken = a.addProfile("Ken").id
        a.settings.setActiveProfileId(ken)
        a.settings.updatePlayer { it.copy(skipIntroRecap = SkipPromptMode.AUTO, skipCredits = SkipPromptMode.OFF, nextEpisodePrompt = false, onlineSkipTimes = false) }
        val file = a.exporter.export(BackupScope.PROFILE, ken, includeCredentials = false).json

        val b = BackupRig()
        val target = b.addProfile("Living room").id
        b.restorer.restore(file, target)

        val player = b.settings.profileSettings(target).player
        assertEquals(SkipPromptMode.AUTO, player.skipIntroRecap)
        assertEquals(SkipPromptMode.OFF, player.skipCredits)
        assertFalse(player.nextEpisodePrompt)
        assertFalse(player.onlineSkipTimes)
    }
}
