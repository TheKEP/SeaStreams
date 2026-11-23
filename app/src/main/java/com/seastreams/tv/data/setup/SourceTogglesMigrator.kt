package com.seastreams.tv.data.setup

import com.seastreams.tv.core.database.dao.NewznabIndexerDao
import com.seastreams.tv.core.database.dao.UsenetProviderDao
import com.seastreams.tv.core.datastore.UsenetSettingsStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time upgrade for the `Enable Usenet` toggle (PRD §11, ARCHITECTURE §10): an install that already had at least
 * one provider and one indexer when the toggle shipped keeps its Usenet results (toggle ON); fresh or empty installs
 * keep the default OFF. Runs once (marker `usenet/enabled_migrated`), so a later choice is never overridden.
 */
@Singleton
class SourceTogglesMigrator @Inject constructor(
    private val providers: UsenetProviderDao,
    private val indexers: NewznabIndexerDao,
    private val store: UsenetSettingsStore,
) {
    suspend fun migrateIfNeeded() {
        if (store.toggleMigrated()) return
        val configured = providers.getAll().isNotEmpty() && indexers.getAll().isNotEmpty()
        store.finishToggleMigration(enable = configured)
    }
}
