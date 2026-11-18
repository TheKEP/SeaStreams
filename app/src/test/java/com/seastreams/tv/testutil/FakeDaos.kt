package com.seastreams.tv.testutil

import com.seastreams.tv.core.database.dao.AddonDao
import com.seastreams.tv.core.database.dao.ProfileDao
import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.database.entity.LibrarySortOrder
import com.seastreams.tv.core.database.entity.ProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [AddonDao] mirroring the Room queries' semantics: rows are keyed by
 * `(profileId, manifestUrl)` and every query is scoped to one profile. [profileIds] stands in for
 * the `profiles` table (only [profileIdsMissing] reads it).
 */
class FakeAddonDao(
    initial: List<AddonEntity> = emptyList(),
    private val profileIds: () -> List<Long> = { emptyList() },
) : AddonDao {
    val rows = MutableStateFlow(initial.associateBy { it.key })

    fun ordered(profileId: Long): List<AddonEntity> =
        rows.value.values.filter { it.profileId == profileId }.sortedBy { it.sortOrder }

    override fun observeAll(profileId: Long): Flow<List<AddonEntity>> = rows.map { ordered(profileId) }
    override fun observeEnabled(profileId: Long): Flow<List<AddonEntity>> =
        observeAll(profileId).map { list -> list.filter { it.enabled } }
    override suspend fun getAll(profileId: Long): List<AddonEntity> = ordered(profileId)
    override suspend fun getByUrl(profileId: Long, manifestUrl: String): AddonEntity? = rows.value[profileId to manifestUrl]
    override suspend fun nextSortOrder(profileId: Long): Int = (ordered(profileId).maxOfOrNull { it.sortOrder } ?: -1) + 1
    override suspend fun profileIdsMissing(manifestUrl: String): List<Long> =
        profileIds().filterNot { rows.value.containsKey(it to manifestUrl) }
    override suspend fun upsert(addon: AddonEntity) = rows.update { it + (addon.key to addon) }

    override suspend fun insertIfAbsent(addons: List<AddonEntity>): List<Long> = addons.map { addon ->
        if (rows.value.containsKey(addon.key)) -1L else { upsert(addon); 1L }
    }

    override suspend fun setEnabled(profileId: Long, manifestUrl: String, enabled: Boolean) =
        edit(profileId, manifestUrl) { copy(enabled = enabled) }
    override suspend fun rename(profileId: Long, manifestUrl: String, customName: String?) =
        edit(profileId, manifestUrl) { copy(customName = customName) }
    override suspend fun updateSortOrder(profileId: Long, manifestUrl: String, sortOrder: Int) =
        edit(profileId, manifestUrl) { copy(sortOrder = sortOrder) }
    override suspend fun delete(profileId: Long, manifestUrl: String) = rows.update { it - (profileId to manifestUrl) }
    override suspend fun deleteAllForProfile(profileId: Long) =
        rows.update { m -> m.filterValues { it.profileId != profileId } }

    private fun edit(profileId: Long, url: String, change: AddonEntity.() -> AddonEntity) =
        rows.update { m -> m[profileId to url]?.let { m + ((profileId to url) to it.change()) } ?: m }

    private val AddonEntity.key get() = profileId to manifestUrl
}

class FakeProfileDao : ProfileDao {
    val rows = MutableStateFlow<List<ProfileEntity>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<ProfileEntity>> = rows.map { list -> list.sortedBy { it.createdAtMillis } }
    override fun observeById(profileId: Long): Flow<ProfileEntity?> = rows.map { list -> list.firstOrNull { it.id == profileId } }
    override suspend fun getById(profileId: Long): ProfileEntity? = rows.value.firstOrNull { it.id == profileId }
    override suspend fun count(): Int = rows.value.size

    override suspend fun insert(profile: ProfileEntity): Long {
        val id = nextId++
        rows.update { it + profile.copy(id = id) }
        return id
    }

    override suspend fun update(profile: ProfileEntity) =
        rows.update { list -> list.map { if (it.id == profile.id) profile else it } }

    override suspend fun updateLibrarySortOrder(profileId: Long, sortOrder: LibrarySortOrder) =
        rows.update { list -> list.map { if (it.id == profileId) it.copy(librarySortOrder = sortOrder) else it } }

    override suspend fun delete(profile: ProfileEntity) = rows.update { list -> list.filterNot { it.id == profile.id } }
}
