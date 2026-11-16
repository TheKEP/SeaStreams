package com.seastreams.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.seastreams.tv.core.database.entity.CacheEntryEntity

@Dao
interface CacheDao {

    /** Returns the payload only while it is still fresh at [nowMillis]. */
    @Query("SELECT payload FROM cache_entries WHERE cacheKey = :cacheKey AND expiresAtMillis > :nowMillis")
    suspend fun getFresh(cacheKey: String, nowMillis: Long): String?

    @Upsert
    suspend fun put(entry: CacheEntryEntity)

    @Query("DELETE FROM cache_entries WHERE expiresAtMillis <= :nowMillis")
    suspend fun deleteExpired(nowMillis: Long): Int

    @Query("DELETE FROM cache_entries")
    suspend fun clear()
}
