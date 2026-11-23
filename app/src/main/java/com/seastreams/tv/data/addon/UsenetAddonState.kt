package com.seastreams.tv.data.addon

import com.seastreams.tv.core.database.dao.NewznabIndexerDao
import com.seastreams.tv.core.database.dao.UsenetProviderDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Usenet streaming is configured (PRD §12): at least one enabled provider and one enabled indexer. Read straight
 * from the two DAOs (no secret decryption), so addon lists can show or hide the built-in `Usenet` entry cheaply.
 */
@Singleton
class UsenetAddonState @Inject constructor(providers: UsenetProviderDao, indexers: NewznabIndexerDao) {
    val configured: Flow<Boolean> = combine(providers.observeAll(), indexers.observeAll()) { p, i ->
        p.any { it.enabled } && i.any { it.enabled }
    }.distinctUntilChanged()
}
