package com.seastreams.tv.testutil

import com.seastreams.tv.core.database.dao.NextUpDismissalDao
import com.seastreams.tv.core.database.entity.NextUpDismissalEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory [NextUpDismissalDao] keyed like Room's (profileId, tmdbId) primary key. */
class FakeNextUpDismissalDao : NextUpDismissalDao {
    val rows = MutableStateFlow<List<NextUpDismissalEntity>>(emptyList())

    override fun observe(profileId: Long): Flow<List<NextUpDismissalEntity>> = rows.map { list -> list.filter { it.profileId == profileId } }

    override suspend fun getAll(profileId: Long): List<NextUpDismissalEntity> = rows.value.filter { it.profileId == profileId }

    override suspend fun upsert(dismissal: NextUpDismissalEntity) = rows.update { list ->
        list.filterNot { it.profileId == dismissal.profileId && it.tmdbId == dismissal.tmdbId } + dismissal
    }

    override suspend fun upsertAll(dismissals: List<NextUpDismissalEntity>) = dismissals.forEach { upsert(it) }
}
