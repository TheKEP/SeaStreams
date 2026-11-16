package com.seastreams.tv.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Which tab a user-added MDBList catalog row appears on. */
enum class CatalogPlacement { HOME, MOVIES, TV_SHOWS }

/** A user-added MDBList catalog row (added through the Web Portal). */
@Entity(
    tableName = "custom_catalogs",
    indices = [Index(value = ["listUrl", "placement"], unique = true)],
)
data class CustomCatalogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listUrl: String,
    val title: String,
    val placement: CatalogPlacement,
    val sortOrder: Int,
    val addedAtMillis: Long,
)
