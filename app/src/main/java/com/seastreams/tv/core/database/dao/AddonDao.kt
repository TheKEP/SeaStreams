package com.seastreams.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.seastreams.tv.core.database.entity.AddonEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AddonDao {

    @Query("SELECT * FROM addons ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<AddonEntity>>

    @Query("SELECT * FROM addons WHERE enabled = 1 ORDER BY sortOrder ASC")
    fun observeEnabled(): Flow<List<AddonEntity>>

    @Query("SELECT * FROM addons ORDER BY sortOrder ASC")
    suspend fun getAll(): List<AddonEntity>

    @Query("SELECT * FROM addons WHERE manifestUrl = :manifestUrl")
    suspend fun getByUrl(manifestUrl: String): AddonEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM addons")
    suspend fun nextSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(addon: AddonEntity)

    /** Inserts only addons whose manifest URL is not installed yet (used for first-launch seeding). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(addons: List<AddonEntity>): List<Long>

    @Query("UPDATE addons SET enabled = :enabled WHERE manifestUrl = :manifestUrl")
    suspend fun setEnabled(manifestUrl: String, enabled: Boolean)

    @Query("UPDATE addons SET customName = :customName WHERE manifestUrl = :manifestUrl")
    suspend fun rename(manifestUrl: String, customName: String?)

    @Query("UPDATE addons SET sortOrder = :sortOrder WHERE manifestUrl = :manifestUrl")
    suspend fun updateSortOrder(manifestUrl: String, sortOrder: Int)

    /** Rewrites sort orders so addons appear in exactly the order of [manifestUrlsInOrder]. */
    @Transaction
    suspend fun reorder(manifestUrlsInOrder: List<String>) {
        manifestUrlsInOrder.forEachIndexed { index, url -> updateSortOrder(url, index) }
    }

    @Query("DELETE FROM addons WHERE manifestUrl = :manifestUrl")
    suspend fun delete(manifestUrl: String)
}
