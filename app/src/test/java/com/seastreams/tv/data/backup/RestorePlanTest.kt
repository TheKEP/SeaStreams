package com.seastreams.tv.data.backup

import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.backup.BackupSamples.backup
import com.seastreams.tv.data.backup.BackupSamples.entry
import com.seastreams.tv.data.backup.BackupSamples.profileRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RestorePlanTest {

    private val tv = listOf(profileRow(1, "Ken"), profileRow(2, "Kids"), profileRow(3, "Guest"))

    @Test
    fun `a profile backup lands on the chosen profile and never touches its fields`() {
        val plan = RestorePlan.of(backup(BackupScope.PROFILE, entry("Someone else")), tv, targetProfileId = 2, hasTmdbKey = true)

        assertEquals(listOf(2L), plan.profiles.map { it.existingId })
        assertFalse(plan.profiles.single().applyMeta)
        assertEquals(0, plan.creates)
    }

    @Test
    fun `a profile backup needs an existing target`() {
        val error = assertThrows(UserFacingException::class.java) {
            RestorePlan.of(backup(BackupScope.PROFILE, entry("Ken")), tv, targetProfileId = 99, hasTmdbKey = true)
        }
        assertEquals("Pick the profile to restore this backup into", error.message)
    }

    @Test
    fun `an app backup matches names ignoring case and creates the rest`() {
        val plan = RestorePlan.of(backup(BackupScope.APP, entry("kids"), entry("Grandma"), entry(" KEN ")), tv, null, hasTmdbKey = true)

        assertEquals(listOf(2L, null, 1L), plan.profiles.map { it.existingId })
        assertTrue(plan.profiles.all { it.applyMeta })
        assertEquals(1, plan.creates)
    }

    @Test
    fun `an app backup that wouldn't fit under five profiles is refused`() {
        val error = assertThrows(UserFacingException::class.java) {
            RestorePlan.of(backup(BackupScope.APP, entry("Ken"), entry("A"), entry("B"), entry("C")), tv, null, hasTmdbKey = true)
        }
        assertEquals(
            "This backup needs 3 new profiles but only 2 of 5 slots are free. Delete or rename profiles on the TV first.",
            error.message,
        )
    }

    @Test
    fun `new profiles need a TMDB key, matched ones don't`() {
        assertThrows(UserFacingException::class.java) { RestorePlan.of(backup(BackupScope.APP, entry("New")), tv, null, hasTmdbKey = false) }
        assertEquals(0, RestorePlan.of(backup(BackupScope.APP, entry("Ken")), tv, null, hasTmdbKey = false).creates)
    }
}
