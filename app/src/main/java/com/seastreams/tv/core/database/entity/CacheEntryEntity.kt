package com.seastreams.tv.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable key/value cache for parsed payloads that must outlive OkHttp's LRU disk cache
 * (for example resolved IMDb ids). Entries are ignored once [expiresAtMillis] has passed.
 */
@Entity(tableName = "cache_entries", indices = [Index("expiresAtMillis")])
data class CacheEntryEntity(
    @PrimaryKey val cacheKey: String,
    val payload: String,
    val cachedAtMillis: Long,
    val expiresAtMillis: Long,
)
