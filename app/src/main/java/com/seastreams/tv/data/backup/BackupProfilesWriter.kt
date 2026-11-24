package com.seastreams.tv.data.backup

import com.seastreams.tv.core.database.dao.AddonDao
import com.seastreams.tv.core.database.dao.ProfileDao
import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.addon.BuiltInAddons
import com.seastreams.tv.data.profile.ProfileValidation
import java.time.Clock
import javax.inject.Inject

/**
 * The profile and addon half of a restore's Room transaction. Lock-free on purpose: `BackupRestorer` already holds the
 * profile, addon and source locks, which aren't re-entrant.
 */
internal class BackupProfilesWriter @Inject constructor(
    private val profiles: ProfileDao,
    private val addons: AddonDao,
    private val clock: Clock,
) {

    /** Applies [plan]; returns each planned profile's id on the TV, in plan order. */
    suspend fun write(plan: RestorePlan): List<Long> {
        val now = clock.millis()
        return plan.profiles.mapIndexed { index, planned ->
            val meta = planned.entry.profile
            val id = when (val existingId = planned.existingId) {
                null -> {
                    val draft = AppBackupCodec.draftOf(meta)
                    // Consecutive creation times keep the backup's profile order on the picker.
                    profiles.insert(ProfileValidation.entity(draft, 0, now + index).copy(librarySortOrder = AppBackupCodec.sortOrderOf(meta)))
                }
                else -> {
                    val existing = profiles.getById(existingId) ?: throw UserFacingException("That profile no longer exists")
                    if (planned.applyMeta) {
                        val draft = AppBackupCodec.draftOf(meta)
                        profiles.update(
                            ProfileValidation.entity(draft, existing.id, existing.createdAtMillis).copy(librarySortOrder = AppBackupCodec.sortOrderOf(meta)),
                        )
                    }
                    existing.id
                }
            }
            replaceAddons(id, planned.entry.addons, now)
            id
        }
    }

    /**
     * Makes [profileId]'s list the backup's, in its order. A built-in entry the backup doesn't list (say, a backup taken
     * before Usenet or Debrid was set up) keeps the profile's own row, with its name and on/off state, at the bottom
     * (PRD §8: hidden built-in entries keep both). One the profile doesn't have either is left to `BuiltInAddonGuard`,
     * which `BackupRestorer` runs once the restored sources are committed. TMDB is always present.
     */
    private suspend fun replaceAddons(profileId: Long, backup: List<AddonBackup>, now: Long) {
        val existing = addons.getAll(profileId).associateBy { it.manifestUrl }
        val restored = backup.sortedBy { it.sortOrder }.map { a ->
            AddonEntity(
                profileId = profileId,
                manifestUrl = a.manifestUrl,
                manifestId = a.manifestId,
                name = a.name,
                customName = a.customName,
                enabled = a.enabled,
                sortOrder = 0,
                manifestJson = a.manifestJson,
                installedAtMillis = existing[a.manifestUrl]?.installedAtMillis ?: now,
            )
        }
        val listed = restored.map { it.manifestUrl }.toSet()
        val kept = BUILT_IN_URLS.filter { it !in listed }.mapNotNull { existing[it] }
        val tmdb = if (BuiltInAddons.TMDB_URL in listed || kept.any { it.manifestUrl == BuiltInAddons.TMDB_URL }) {
            emptyList()
        } else {
            listOf(BuiltInAddons.tmdb(profileId, 0, now))
        }
        addons.deleteAllForProfile(profileId)
        (restored + kept + tmdb).forEachIndexed { index, row -> addons.upsert(row.copy(sortOrder = index)) }
    }

    private companion object {
        val BUILT_IN_URLS = listOf(BuiltInAddons.TMDB_URL, BuiltInAddons.USENET_URL, BuiltInAddons.DEBRID_URL)
    }
}
