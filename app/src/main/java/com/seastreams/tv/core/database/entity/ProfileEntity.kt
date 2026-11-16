package com.seastreams.tv.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.seastreams.tv.domain.model.AgeRestriction
import com.seastreams.tv.domain.model.MovieCertification
import com.seastreams.tv.domain.model.TvCertification

/** Library sort orders (PRD §7), persisted per profile. */
enum class LibrarySortOrder { A_TO_Z, Z_TO_A, LAST_ADDED, LAST_WATCHED, NOT_WATCHED }

/**
 * A viewer profile (max 5, enforced by the profile repository).
 * Kids profiles store their age ceilings; Adult profiles leave them null.
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val avatarIndex: Int,
    val isKids: Boolean,
    val movieCeiling: MovieCertification?,
    val tvCeiling: TvCertification?,
    val allowUnrated: Boolean,
    val librarySortOrder: LibrarySortOrder = LibrarySortOrder.A_TO_Z,
    val createdAtMillis: Long,
) {
    /** Restriction to feed the age engine; `null` for Adult profiles. */
    fun toAgeRestriction(): AgeRestriction? {
        if (!isKids) return null
        return AgeRestriction(
            movieCeiling = movieCeiling ?: MovieCertification.G,
            tvCeiling = tvCeiling ?: TvCertification.TV_Y,
            allowUnrated = allowUnrated,
        )
    }
}
