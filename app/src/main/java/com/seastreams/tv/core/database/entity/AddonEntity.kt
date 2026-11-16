package com.seastreams.tv.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** An installed Stremio addon, keyed by its manifest URL. */
@Entity(tableName = "addons", indices = [Index("sortOrder")])
data class AddonEntity(
    @PrimaryKey val manifestUrl: String,
    val manifestId: String,
    val name: String,
    /** User-chosen display name from the Web Portal; overrides [name] when set. */
    val customName: String?,
    val enabled: Boolean,
    val sortOrder: Int,
    /** Raw manifest JSON, re-parsed by the addon client so new manifest fields need no migration. */
    val manifestJson: String,
    val installedAtMillis: Long,
)
