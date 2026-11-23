package com.seastreams.tv.data.addon

import com.seastreams.tv.core.database.dao.NewznabIndexerDao
import com.seastreams.tv.core.database.dao.UsenetProviderDao
import com.seastreams.tv.core.datastore.UsenetSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Usenet streaming is on and configured (PRD §11, §12): `Enable Usenet` is ON and at least one provider and one
 * indexer are enabled. Read straight from the toggle and the two DAOs (no secret decryption), so addon lists can show
 * or hide the built-in `Usenet` entry cheaply. Switching the toggle off hides the entry without touching any row.
 */
@Singleton
class UsenetAddonState @Inject constructor(
    providers: UsenetProviderDao,
    indexers: NewznabIndexerDao,
    settings: UsenetSettingsStore,
) {
    val configured: Flow<Boolean> = combine(settings.enabled, providers.observeAll(), indexers.observeAll()) { on, p, i ->
        on && p.any { it.enabled } && i.any { it.enabled }
    }.distinctUntilChanged()
}
