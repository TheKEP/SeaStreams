package com.seastreams.tv.data.backup

import com.seastreams.tv.core.database.entity.WatchProgressEntity
import com.seastreams.tv.domain.model.MediaType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchProgressMergeTest {

    private fun row(position: Long, updated: Long, duration: Long = 100) =
        WatchProgressEntity(1, 550, MediaType.MOVIE, 0, 0, "Fight Club", null, null, null, position, duration, updated)

    @Test
    fun `the backup's record is used when the TV has none`() = assertTrue(WatchProgressMerge.backupWins(null, row(10, 1)))

    @Test
    fun `the more recently updated record wins, even if it is less far along`() {
        assertTrue(WatchProgressMerge.backupWins(local = row(95, 1), backup = row(10, 2)))
        assertFalse(WatchProgressMerge.backupWins(local = row(10, 2), backup = row(95, 1)))
    }

    @Test
    fun `on equal times the further-watched record wins, and a full tie keeps the TV's`() {
        assertTrue(WatchProgressMerge.backupWins(local = row(40, 5), backup = row(60, 5)))
        assertFalse(WatchProgressMerge.backupWins(local = row(60, 5), backup = row(40, 5)))
        assertFalse(WatchProgressMerge.backupWins(local = row(50, 5), backup = row(50, 5)))
        // Completion is position / duration, not raw position; an unknown duration counts as 0 %.
        assertTrue(WatchProgressMerge.backupWins(local = row(500, 5, duration = 1_000), backup = row(90, 5, duration = 100)))
        assertFalse(WatchProgressMerge.backupWins(local = row(1, 5, duration = 100), backup = row(90, 5, duration = 0)))
    }
}
