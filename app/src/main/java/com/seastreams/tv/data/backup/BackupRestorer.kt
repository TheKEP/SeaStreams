package com.seastreams.tv.data.backup

import com.seastreams.tv.core.database.dao.ProfileDao
import com.seastreams.tv.core.network.TmdbApiKeyProvider
import com.seastreams.tv.core.util.IoDispatcher
import com.seastreams.tv.data.addon.AddonRepository
import com.seastreams.tv.data.debrid.DebridSourcesRepository
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.data.setup.BuiltInAddonGuard
import com.seastreams.tv.data.usenet.UsenetSourcesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/** What a restore did, as the portal reports it. */
@Serializable
data class RestoreSummary(
    val scope: String,
    val profilesUpdated: Int,
    val profilesCreated: Int,
    val providers: Int,
    val indexers: Int,
    val debridAccounts: Int,
    val credentialsRestored: Int,
    /** Library titles added (titles already saved are kept as they were). */
    val libraryItems: Int = 0,
    /** Watch progress records written (the TV's newer records are kept). */
    val watchProgress: Int = 0,
)

/**
 * Restores a backup file (ARCHITECTURE §13):
 * 1. [AppBackupCodec.decode] validates and normalises the whole file; a bad file throws [InvalidAppBackupException] and
 *    nothing is written.
 * 2. Holding the profile → addon → Usenet → Debrid locks (always in that order, and always before the database, the way
 *    `BuiltInAddonGuard` holds the addon lock before its writes), the [RestorePlan] is made against the TV's current
 *    profiles (a refused plan writes nothing), then profiles, addons, Library, Watch Progress (both merged without
 *    rewinding the TV, [BackupHistoryWriter]) and sources are written in one Room transaction
 *    and every preference in one DataStore edit.
 * 3. With the locks released, [BuiltInAddonGuard.refreshSourceEntries] appends any `Usenet` / `Debrid` entry a restored
 *    profile is missing now that the restored sources are configured: its background upkeep can't see a list that was
 *    replaced while the source stayed on.
 *
 * The Room commit and the DataStore edit can't share one transaction; restoring the same file again is safe.
 */
@Singleton
class BackupRestorer @Inject internal constructor(
    private val profileRepository: ProfileRepository,
    private val addonRepository: AddonRepository,
    private val usenetSources: UsenetSourcesRepository,
    private val debridSources: DebridSourcesRepository,
    private val profileDao: ProfileDao,
    private val tmdbKey: TmdbApiKeyProvider,
    private val profilesWriter: BackupProfilesWriter,
    private val historyWriter: BackupHistoryWriter,
    private val sourcesWriter: BackupSourcesWriter,
    private val preferencesWriter: BackupPreferencesWriter,
    private val transactor: BackupTransactor,
    private val builtInGuard: BuiltInAddonGuard,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Restores [text]; a `profile` backup lands on [targetProfileId]. */
    suspend fun restore(text: String, targetProfileId: Long?): RestoreSummary = withContext(io) {
        val backup = AppBackupCodec.decode(text)
        val summary = profileRepository.withWriteLock {
            addonRepository.withOrderLock {
                usenetSources.withOrderLock {
                    debridSources.withLock { apply(backup, targetProfileId) }
                }
            }
        }
        builtInGuard.refreshSourceEntries()
        summary
    }

    private suspend fun apply(backup: AppBackup, targetProfileId: Long?): RestoreSummary {
        val existing = profileDao.observeAll().first()
        val hasKey = !tmdbKey.currentApiKey().isNullOrBlank() || !backup.tmdbApiKey.isNullOrBlank()
        val plan = RestorePlan.of(backup, existing, targetProfileId, hasKey)
        val (ids, sources, history) = transactor.inTransaction {
            val ids = profilesWriter.write(plan)
            val history = ids.zip(plan.profiles).fold(RestoredHistory(0, 0)) { total, (id, planned) ->
                total + historyWriter.write(id, planned.entry)
            }
            Triple(ids, sourcesWriter.write(backup.usenet, backup.debrid), history)
        }
        debridSources.invalidateSignIns(sources.resealedDebrid)
        val allIds = existing.map { it.id }.toSet() + ids
        preferencesWriter.write(backup, ids.zip(plan.profiles.map { it.entry }), allIds)
        ids.forEach(profileRepository::forgetCatalogs)
        val credentials = sources.credentials + if (backup.tmdbApiKey.isNullOrBlank()) 0 else 1
        return RestoreSummary(
            scope = plan.scope.wire,
            profilesUpdated = plan.profiles.size - plan.creates,
            profilesCreated = plan.creates,
            providers = sources.providers,
            indexers = sources.indexers,
            debridAccounts = sources.debridAccounts,
            credentialsRestored = credentials,
            libraryItems = history.libraryItems,
            watchProgress = history.watchProgress,
        )
    }
}
