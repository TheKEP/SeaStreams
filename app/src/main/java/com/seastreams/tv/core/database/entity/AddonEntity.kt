package com.seastreams.tv.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A Stremio addon installed on one profile, keyed by profile + manifest URL. Each profile has its
 * own list (order, enabled state, custom names); deleting the profile deletes its addons.
 */
@Entity(
    tableName = "addons",
    primaryKeys = ["profileId", "manifestUrl"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId"), Index("profileId", "sortOrder")],
)
data class AddonEntity(
    val profileId: Long,
    val manifestUrl: String,
    val manifestId: String,
    val name: String,
    /** User-chosen display name from the Web Portal; overrides [name] when set. */
    val customName: String?,
    val enabled: Boolean,
    /** Position within this profile's list (dense, starting at 0). */
    val sortOrder: Int,
    /** Raw manifest JSON, re-parsed by the addon client so new manifest fields need no migration. */
    val manifestJson: String,
    val installedAtMillis: Long,
)
