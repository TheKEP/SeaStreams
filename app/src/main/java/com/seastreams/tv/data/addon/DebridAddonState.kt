package com.seastreams.tv.data.addon

import com.seastreams.tv.core.database.dao.DebridProviderDao
import com.seastreams.tv.core.datastore.DebridSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the built-in `Debrid` entry is listed (PRD §8, §14): `Enable Debrid` is ON and at least one Debrid provider
 * is enabled. Read straight from the toggle and the DAO (no secret decryption), so addon lists can show or hide the
 * entry cheaply. Switching the toggle off hides the entry without touching any row.
 */
@Singleton
class DebridAddonState @Inject constructor(providers: DebridProviderDao, settings: DebridSettingsStore) {
    val listed: Flow<Boolean> = combine(settings.enabled, providers.observeAll()) { on, rows -> on && rows.any { it.enabled } }
        .distinctUntilChanged()
}
