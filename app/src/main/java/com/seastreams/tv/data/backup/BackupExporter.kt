package com.seastreams.tv.data.backup

import com.seastreams.tv.core.database.dao.AddonDao
import com.seastreams.tv.core.database.dao.DebridProviderDao
import com.seastreams.tv.core.database.dao.LibraryDao
import com.seastreams.tv.core.database.dao.NewznabIndexerDao
import com.seastreams.tv.core.database.dao.NextUpDismissalDao
import com.seastreams.tv.core.database.dao.ProfileDao
import com.seastreams.tv.core.database.dao.UsenetProviderDao
import com.seastreams.tv.core.database.dao.WatchProgressDao
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.datastore.DebridSettingsStore
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.datastore.StreamProcessingStore
import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.core.security.SecretCipher
import com.seastreams.tv.core.util.IoDispatcher
import com.seastreams.tv.data.UserFacingException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** A finished export: the download's file name and its JSON body. [toString] never prints the body (it may hold secrets). */
class BackupFile(val fileName: String, val json: String) {
    override fun toString(): String = "BackupFile($fileName, ${json.length} chars)"
}

/**
 * Builds backup files (PRD §8 *Backup & restore*), each profile with its Library and Watch Progress. Hidden built-in addon rows are exported too, so a restore keeps
 * their position and on/off state. Secrets are decrypted only for an [includeCredentials] export; one that can't be
 * decrypted on this device is left out like any other secret. Nothing here logs.
 */
@Singleton
class BackupExporter @Inject constructor(
    private val profiles: ProfileDao,
    private val addons: AddonDao,
    private val settings: SettingsDataStore,
    private val streams: StreamProcessingStore,
    private val usenetSettings: UsenetSettingsStore,
    private val debridSettings: DebridSettingsStore,
    private val providers: UsenetProviderDao,
    private val indexers: NewznabIndexerDao,
    private val debrid: DebridProviderDao,
    private val cipher: SecretCipher,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
    private val library: LibraryDao,
    private val watchProgress: WatchProgressDao,
    private val nextUpDismissals: NextUpDismissalDao,
) {

    suspend fun export(scope: BackupScope, profileId: Long?, includeCredentials: Boolean): BackupFile = withContext(io) {
        val all = profiles.observeAll().first()
        val chosen = when (scope) {
            BackupScope.APP -> all.ifEmpty { throw UserFacingException("There are no profiles to back up yet") }
            BackupScope.PROFILE -> listOf(all.firstOrNull { it.id == profileId } ?: throw UserFacingException("That profile no longer exists"))
        }
        val backup = AppBackup(
            version = APP_BACKUP_VERSION,
            scope = scope.wire,
            createdAtMillis = clock.millis(),
            includesCredentials = includeCredentials,
            tmdbApiKey = if (includeCredentials) settings.tmdbApiKey.first() else null,
            usenet = usenet(includeCredentials),
            debrid = debrid(includeCredentials),
            profiles = chosen.map { profileEntry(it) },
        )
        val name = if (scope == BackupScope.APP) BackupFileNames.FULL_BACKUP else BackupFileNames.profileBackup(chosen.first().name)
        BackupFile(name, AppBackupCodec.encode(backup))
    }

    private suspend fun profileEntry(profile: ProfileEntity) = ProfileBackupEntry(
        profile = ProfileMetaBackup(
            name = profile.name,
            avatarIndex = profile.avatarIndex,
            isKids = profile.isKids,
            movieCeiling = profile.movieCeiling?.name,
            tvCeiling = profile.tvCeiling?.name,
            allowUnrated = profile.allowUnrated,
            librarySortOrder = profile.librarySortOrder.name,
        ),
        settings = GeneralSettingsBackup.of(settings.profileSettings(profile.id)),
        addons = addons.getAll(profile.id).map {
            AddonBackup(it.manifestUrl, it.manifestId, it.name, it.customName, it.enabled, it.sortOrder, it.manifestJson)
        },
        streams = streams.exportBackup(profile.id),
        library = library.getAll(profile.id).sortedBy { it.addedAtMillis }.map(LibraryItemBackup::of),
        watchProgress = watchProgress.getAll(profile.id).map(WatchProgressBackup::of),
        nextUpDismissals = nextUpDismissals.getAll(profile.id).sortedBy { it.tmdbId }.map(NextUpDismissalBackup::of),
    )

    private suspend fun usenet(secrets: Boolean): UsenetBackup {
        val headers = usenetSettings.headers.first()
        return UsenetBackup(
            enabled = usenetSettings.enabled.first(),
            performance = usenetSettings.performance.first(),
            searchHeaders = headers.search.name,
            downloadHeaders = headers.download.name,
            providers = providers.getAll().map { p ->
                NntpProviderBackup(
                    name = p.name, host = p.host, port = p.port, tls = p.tls, username = p.username,
                    password = open(secrets, p.passwordCipher),
                    maxConnections = p.maxConnections, pipelineDepth = p.pipelineDepth, priority = p.priority,
                    isBackup = p.isBackup, enabled = p.enabled, sortOrder = p.sortOrder,
                )
            },
            indexers = indexers.getAll().map { i ->
                IndexerBackup(
                    name = i.name, baseUrl = i.baseUrl, apiPath = i.apiPath, apiKey = open(secrets, i.apiKeyCipher),
                    priority = i.priority, backupOnly = i.backupOnly, enabled = i.enabled, sortOrder = i.sortOrder,
                )
            },
        )
    }

    private suspend fun debrid(secrets: Boolean) = DebridBackup(
        enabled = debridSettings.enabled.first(),
        providers = debrid.getAll().map { d ->
            val credential = open(secrets, d.credentialCipher)
            DebridProviderBackup(
                providerType = d.providerType,
                name = d.name,
                authMethod = d.authMethod,
                enabled = d.enabled,
                sortOrder = d.sortOrder,
                credential = credential,
                // Tokens and expiry only mean something next to the access token they belong to.
                refreshToken = credential?.let { open(true, d.refreshTokenCipher) },
                clientId = credential?.let { open(true, d.clientIdCipher) },
                clientSecret = credential?.let { open(true, d.clientSecretCipher) },
                expiresAtMillis = credential?.let { d.expiresAtMillis },
            )
        },
    )

    private fun open(secrets: Boolean, stored: String?): String? =
        if (secrets && !stored.isNullOrEmpty()) cipher.decrypt(stored)?.takeIf { it.isNotEmpty() } else null
}
