package com.seastreams.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.seastreams.tv.core.database.entity.NewznabIndexerEntity
import com.seastreams.tv.core.database.entity.UsenetProviderEntity
import kotlinx.coroutines.flow.Flow

/** App-wide NNTP providers, in list order. */
@Dao
interface UsenetProviderDao {

    @Query("SELECT * FROM usenet_providers ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<UsenetProviderEntity>>

    @Query("SELECT * FROM usenet_providers ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<UsenetProviderEntity>

    @Query("SELECT * FROM usenet_providers WHERE id = :id")
    suspend fun get(id: Long): UsenetProviderEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM usenet_providers")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(provider: UsenetProviderEntity): Long

    @Update
    suspend fun update(provider: UsenetProviderEntity)

    @Query("UPDATE usenet_providers SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE usenet_providers SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    /** Rewrites sort orders so providers appear exactly in [idsInOrder]. */
    @Transaction
    suspend fun reorder(idsInOrder: List<Long>) {
        idsInOrder.forEachIndexed { index, id -> updateSortOrder(id, index) }
    }

    @Query("DELETE FROM usenet_providers WHERE id = :id")
    suspend fun delete(id: Long)

    /** `Clear Usenet settings`. */
    @Query("DELETE FROM usenet_providers")
    suspend fun deleteAll()
}

/** App-wide Newznab indexers, in list order. */
@Dao
interface NewznabIndexerDao {

    @Query("SELECT * FROM newznab_indexers ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<NewznabIndexerEntity>>

    @Query("SELECT * FROM newznab_indexers ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<NewznabIndexerEntity>

    @Query("SELECT * FROM newznab_indexers WHERE id = :id")
    suspend fun get(id: Long): NewznabIndexerEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM newznab_indexers")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(indexer: NewznabIndexerEntity): Long

    @Update
    suspend fun update(indexer: NewznabIndexerEntity)

    @Query("UPDATE newznab_indexers SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE newznab_indexers SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Transaction
    suspend fun reorder(idsInOrder: List<Long>) {
        idsInOrder.forEachIndexed { index, id -> updateSortOrder(id, index) }
    }

    @Query("DELETE FROM newznab_indexers WHERE id = :id")
    suspend fun delete(id: Long)

    /** `Clear Usenet settings`. */
    @Query("DELETE FROM newznab_indexers")
    suspend fun deleteAll()
}
