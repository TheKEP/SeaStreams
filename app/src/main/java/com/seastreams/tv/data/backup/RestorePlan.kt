package com.seastreams.tv.data.backup

import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.profile.ProfileRepository
import java.util.Locale

/**
 * Where one backed-up profile lands: onto the TV's profile [existingId], or a new profile when `null`. [applyMeta] is
 * whether its name, avatar, Kids flag and age ceilings are written too (never for a single-profile restore, so a
 * restore can't lift a Kids profile's limits).
 */
internal data class PlannedProfile(val entry: ProfileBackupEntry, val existingId: Long?, val applyMeta: Boolean)

/** What a restore will do to the TV's profiles, decided before anything is written. */
internal class RestorePlan(val scope: BackupScope, val profiles: List<PlannedProfile>) {

    val creates: Int get() = profiles.count { it.existingId == null }

    companion object {
        /**
         * - `profile` scope: the one entry goes onto [targetProfileId], which must exist; its profile fields are kept.
         * - `app` scope: each entry, in order, updates the first not-yet-used profile with the same name (ignoring case),
         *   else becomes a new profile. Profiles the backup doesn't mention are left alone. Refused when the new ones
         *   wouldn't fit under [ProfileRepository.MAX_PROFILES], or when profiles must be created without a TMDB key.
         */
        fun of(backup: AppBackup, existing: List<ProfileEntity>, targetProfileId: Long?, hasTmdbKey: Boolean): RestorePlan {
            val scope = BackupScope.fromWire(backup.scope) ?: throw UserFacingException("Unknown backup type")
            if (scope == BackupScope.PROFILE) {
                val target = existing.firstOrNull { it.id == targetProfileId }
                    ?: throw UserFacingException("Pick the profile to restore this backup into")
                return RestorePlan(scope, listOf(PlannedProfile(backup.profiles.single(), target.id, applyMeta = false)))
            }
            val used = mutableSetOf<Long>()
            val planned = backup.profiles.map { entry ->
                val name = entry.profile.name.trim().lowercase(Locale.ROOT)
                val match = existing.firstOrNull { it.id !in used && it.name.trim().lowercase(Locale.ROOT) == name }
                match?.let { used += it.id }
                PlannedProfile(entry, match?.id, applyMeta = true)
            }
            val plan = RestorePlan(scope, planned)
            val free = ProfileRepository.MAX_PROFILES - existing.size
            if (plan.creates > free) {
                throw UserFacingException(
                    "This backup needs ${plan.creates} new profile${if (plan.creates == 1) "" else "s"} but only $free " +
                        "of ${ProfileRepository.MAX_PROFILES} slots are free. Delete or rename profiles on the TV first.",
                )
            }
            if (plan.creates > 0 && !hasTmdbKey) {
                throw UserFacingException("Save a TMDB API key (or restore a backup that includes it) before restoring new profiles")
            }
            return plan
        }
    }
}
