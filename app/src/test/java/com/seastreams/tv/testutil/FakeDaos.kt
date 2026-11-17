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

/** In-memory [AddonDao] mirroring the Room queries' semantics. */
class FakeAddonDao(initial: List<AddonEntity> = emptyList()) : AddonDao {
    val rows = MutableStateFlow(initial.associateBy { it.manifestUrl })

    val ordered: List<AddonEntity> get() = rows.value.values.sortedBy { it.sortOrder }

    override fun observeAll(): Flow<List<AddonEntity>> = rows.map { m -> m.values.sortedBy { it.sortOrder } }
    override fun observeEnabled(): Flow<List<AddonEntity>> = observeAll().map { list -> list.filter { it.enabled } }
    override suspend fun getAll(): List<AddonEntity> = ordered
    override suspend fun getByUrl(manifestUrl: String): AddonEntity? = rows.value[manifestUrl]
    override suspend fun nextSortOrder(): Int = (rows.value.values.maxOfOrNull { it.sortOrder } ?: -1) + 1
    override suspend fun upsert(addon: AddonEntity) = rows.update { it + (addon.manifestUrl to addon) }

    override suspend fun insertIfAbsent(addons: List<AddonEntity>): List<Long> = addons.map { addon ->
        if (rows.value.containsKey(addon.manifestUrl)) -1L else { upsert(addon); 1L }
    }

    override suspend fun setEnabled(manifestUrl: String, enabled: Boolean) = edit(manifestUrl) { copy(enabled = enabled) }
    override suspend fun rename(manifestUrl: String, customName: String?) = edit(manifestUrl) { copy(customName = customName) }
    override suspend fun updateSortOrder(manifestUrl: String, sortOrder: Int) = edit(manifestUrl) { copy(sortOrder = sortOrder) }
    override suspend fun delete(manifestUrl: String) = rows.update { it - manifestUrl }

    private fun edit(url: String, change: AddonEntity.() -> AddonEntity) =
        rows.update { m -> m[url]?.let { m + (url to it.change()) } ?: m }
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
