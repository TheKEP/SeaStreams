package com.seastreams.tv.data.catalog

import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamingProvider
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** What a Movies or TV Shows tab last resolved for a profile: the profile, its service toggles and its row specs. */
data class DiscoverInputs(
    val profile: ProfileEntity,
    val enabledProviders: List<StreamingProvider>,
    val specs: List<CatalogRowSpec>,
)

/**
 * In-memory record, keyed by profile id and tab, of the last [DiscoverInputs] each Movies / TV Shows
 * tab used. Together with the row cache it lets a tab draw its rows on its first frame.
 *
 * Only Adult profiles are recorded (Kids profiles have no such tabs, PRD §1), and a profile's entries
 * are dropped whenever it is edited or deleted, so a profile turned into a Kids profile can never be
 * shown rows recorded under its Adult settings (CLAUDE.md rule 6).
 */
@Singleton
class DiscoverSnapshotStore @Inject constructor() {

    private data class Key(val profileId: Long, val mediaType: MediaType)

    private val entries = ConcurrentHashMap<Key, DiscoverInputs>()

    fun record(mediaType: MediaType, inputs: DiscoverInputs) {
        val key = Key(inputs.profile.id, mediaType)
        if (inputs.profile.isKids) entries.remove(key) else entries[key] = inputs
    }

    fun lookup(profileId: Long, mediaType: MediaType): DiscoverInputs? = entries[Key(profileId, mediaType)]

    /** Drops everything recorded for [profileId]; called when the profile is edited or deleted. */
    fun forget(profileId: Long) {
        entries.keys.removeIf { it.profileId == profileId }
    }
}
