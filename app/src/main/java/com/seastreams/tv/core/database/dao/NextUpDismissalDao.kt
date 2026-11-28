package com.seastreams.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.seastreams.tv.core.database.entity.NextUpDismissalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NextUpDismissalDao {

    @Query("SELECT * FROM next_up_dismissals WHERE profileId = :profileId")
    fun observe(profileId: Long): Flow<List<NextUpDismissalEntity>>

    /** Every dismissal of [profileId] (backup export and restore merge). */
    @Query("SELECT * FROM next_up_dismissals WHERE profileId = :profileId")
    suspend fun getAll(profileId: Long): List<NextUpDismissalEntity>

    /** A newer `Remove` on the same show replaces the older one. */
    @Upsert
    suspend fun upsert(dismissal: NextUpDismissalEntity)

    @Upsert
    suspend fun upsertAll(dismissals: List<NextUpDismissalEntity>)
}
