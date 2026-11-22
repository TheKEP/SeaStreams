package com.seastreams.tv.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An NNTP provider (PRD §12). App-wide: no profile key, every profile shares it. The password is only
 * ever stored as `SecretCipher` output ([passwordCipher], `null` when none was given).
 */
@Entity(tableName = "usenet_providers", indices = [Index("sortOrder")])
data class UsenetProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val username: String,
    val passwordCipher: String?,
    /** 1..100. */
    val maxConnections: Int,
    /** Requests kept in flight per connection, 1..16. */
    val pipelineDepth: Int,
    /** 0..99, lower = preferred; equal priorities share load. */
    val priority: Int,
    /** Only asked for articles the primary providers can't supply. */
    val isBackup: Boolean,
    val enabled: Boolean,
    /** Position in the list (dense, starting at 0). */
    val sortOrder: Int,
)

/** A Newznab indexer or aggregator (PRD §12). App-wide; the API key is stored only as `SecretCipher` output. */
@Entity(tableName = "newznab_indexers", indices = [Index("sortOrder")])
data class NewznabIndexerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Scheme + host (+ port), no trailing slash, e.g. `https://api.nzbgeek.info`. */
    val baseUrl: String,
    /** Starts with `/`, e.g. `/api`. */
    val apiPath: String,
    val apiKeyCipher: String?,
    val priority: Int,
    /** Searched only when no other indexer returned results. */
    val backupOnly: Boolean,
    val enabled: Boolean,
    val sortOrder: Int,
)
