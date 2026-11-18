package com.seastreams.tv.data.setup

import com.seastreams.tv.core.database.dao.ProfileDao
import com.seastreams.tv.core.datastore.SettingsDataStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Upgrades installs that predate per-profile General Settings: every existing profile receives a
 * copy of the old app-wide values, so nothing visibly changes for anyone after the update.
 */
@Singleton
class ProfileSettingsMigrator @Inject constructor(
    private val profileDao: ProfileDao,
    private val settings: SettingsDataStore,
) {
    suspend fun migrateIfNeeded() {
        settings.migrateLegacySettings(profileDao.observeAll().first().map { it.id })
    }
}
