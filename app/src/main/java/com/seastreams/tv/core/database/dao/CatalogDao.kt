package com.seastreams.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.seastreams.tv.core.database.entity.CatalogPlacement
import com.seastreams.tv.core.database.entity.CustomCatalogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {

    @Query("SELECT * FROM custom_catalogs WHERE placement = :placement ORDER BY sortOrder ASC")
    fun observeByPlacement(placement: CatalogPlacement): Flow<List<CustomCatalogEntity>>

    @Query("SELECT * FROM custom_catalogs ORDER BY placement, sortOrder ASC")
    fun observeAll(): Flow<List<CustomCatalogEntity>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM custom_catalogs WHERE placement = :placement")
    suspend fun nextSortOrder(placement: CatalogPlacement): Int

    /** Returns the new row id, or -1 when the same list is already on that tab. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(catalog: CustomCatalogEntity): Long

    @Query("SELECT * FROM custom_catalogs WHERE id = :catalogId")
    suspend fun getById(catalogId: Long): CustomCatalogEntity?

    @Query("DELETE FROM custom_catalogs WHERE id = :catalogId")
    suspend fun delete(catalogId: Long)
}
