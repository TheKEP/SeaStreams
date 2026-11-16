package com.seastreams.tv.data.profile

import com.seastreams.tv.core.database.entity.ProfileEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Per-profile screen content: [content] for the current version of the profile (restarting when it
 * is edited, e.g. its age ceiling changes), or a single [missing] value once it has been deleted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> ProfileRepository.contentForProfile(
    profileId: Long,
    missing: () -> T,
    content: (ProfileEntity) -> Flow<T>,
): Flow<T> = observeProfile(profileId).distinctUntilChanged().flatMapLatest { profile ->
    if (profile == null) flowOf(missing()) else content(profile)
}
