package com.seastreams.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.seastreams.tv.core.database.entity.DebridProviderEntity
import kotlinx.coroutines.flow.Flow

/** App-wide Debrid accounts, in list order (PRD §14). */
@Dao
interface DebridProviderDao {

    @Query("SELECT * FROM debrid_providers ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<DebridProviderEntity>>

    @Query("SELECT * FROM debrid_providers ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<DebridProviderEntity>

    @Query("SELECT * FROM debrid_providers WHERE id = :id")
    suspend fun get(id: Long): DebridProviderEntity?

    @Query("SELECT * FROM debrid_providers WHERE providerType = :providerType")
    suspend fun getByType(providerType: String): DebridProviderEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM debrid_providers")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(provider: DebridProviderEntity): Long

    @Update
    suspend fun update(provider: DebridProviderEntity)

    @Query("UPDATE debrid_providers SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE debrid_providers SET signInNeeded = :signInNeeded WHERE id = :id")
    suspend fun setSignInNeeded(id: Long, signInNeeded: Boolean)

    /** A refreshed device sign-in: new tokens, and the credential is usable again. */
    @Query(
        "UPDATE debrid_providers SET credentialCipher = :credentialCipher, refreshTokenCipher = :refreshTokenCipher, " +
            "expiresAtMillis = :expiresAtMillis, signInNeeded = 0 WHERE id = :id",
    )
    suspend fun updateTokens(id: Long, credentialCipher: String, refreshTokenCipher: String?, expiresAtMillis: Long?)

    @Query("UPDATE debrid_providers SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    /** Rewrites sort orders so providers appear exactly in [idsInOrder]. */
    @Transaction
    suspend fun reorder(idsInOrder: List<Long>) {
        idsInOrder.forEachIndexed { index, id -> updateSortOrder(id, index) }
    }

    @Query("DELETE FROM debrid_providers WHERE id = :id")
    suspend fun delete(id: Long)

    /** `Clear Debrid settings`: every account and its stored keys/tokens. */
    @Query("DELETE FROM debrid_providers")
    suspend fun deleteAll()
}
