package com.seastreams.tv.testutil

import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.database.entity.DebridProviderEntity
import com.seastreams.tv.core.database.entity.LibraryItemEntity
import com.seastreams.tv.core.database.entity.WatchProgressEntity
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.core.database.entity.NewznabIndexerEntity
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.database.entity.UsenetProviderEntity
import com.seastreams.tv.core.datastore.DebridSettingsStore
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.datastore.StreamProcessingStore
import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.data.addon.AddonRepository
import com.seastreams.tv.data.addon.BuiltInAddons
import com.seastreams.tv.data.addon.DebridAddonState
import com.seastreams.tv.data.addon.UsenetAddonState
import com.seastreams.tv.data.backup.BackupExporter
import com.seastreams.tv.data.backup.BackupHistoryWriter
import com.seastreams.tv.data.backup.BackupPreferencesWriter
import com.seastreams.tv.data.backup.BackupProfilesWriter
import com.seastreams.tv.data.backup.BackupRestorer
import com.seastreams.tv.data.backup.BackupSourcesWriter
import com.seastreams.tv.data.backup.BackupTransactor
import com.seastreams.tv.data.catalog.DiscoverSnapshotStore
import com.seastreams.tv.data.catalog.LoadedCatalogStore
import com.seastreams.tv.data.debrid.DebridSourcesRepository
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.data.setup.BuiltInAddonGuard
import com.seastreams.tv.data.usenet.UsenetSourcesRepository
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * One whole "TV" in memory for backup tests: the real stores, repositories, built-in addon guard, exporter and restorer
 * over in-memory DAOs and preferences. [transactions] counts Room transactions the restorer opened.
 */
class BackupRig {
    val clock: Clock = Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC)
    val profileDao = FakeProfileDao()
    val addonDao = FakeAddonDao(profileIds = { profileDao.rows.value.map { it.id } })
    val providerDao = FakeUsenetProviderDao()
    val indexerDao = FakeNewznabIndexerDao()
    val debridDao = FakeDebridProviderDao()
    val libraryDao = FakeLibraryDao()
    val progressDao = FakeWatchProgressDao()
    val dismissalDao = FakeNextUpDismissalDao()
    val prefs = InMemoryPreferences()
    val cipher = FakeSecretCipher()

    val settings = SettingsDataStore(prefs)
    val streams = StreamProcessingStore(prefs)
    val usenetSettings = UsenetSettingsStore(prefs)
    val debridSettings = DebridSettingsStore(prefs)

    private val io = Dispatchers.Unconfined
    private val usenetState = UsenetAddonState(providerDao, indexerDao, usenetSettings)
    private val debridState = DebridAddonState(debridDao, debridSettings)
    val addons = AddonRepository(addonDao, profileDao, mockk(relaxed = true), clock, usenetState, debridState)
    val profiles = ProfileRepository(profileDao, addonDao, settings, settings, clock, DiscoverSnapshotStore(), LoadedCatalogStore(clock))
    val usenetSources = UsenetSourcesRepository(providerDao, indexerDao, cipher, io)
    val debridSources = DebridSourcesRepository(debridDao, cipher, io)
    val guard = BuiltInAddonGuard(addons, usenetState, debridState, profileDao)

    var transactions = 0
        private set

    val exporter = BackupExporter(
        profileDao, addonDao, settings, streams, usenetSettings, debridSettings, providerDao, indexerDao, debridDao, cipher, io, clock,
        libraryDao, progressDao, dismissalDao,
    )

    val restorer = BackupRestorer(
        profiles, addons, usenetSources, debridSources, profileDao, settings,
        BackupProfilesWriter(profileDao, addonDao, clock),
        BackupHistoryWriter(profileDao, libraryDao, progressDao, AgeRestrictionEngine(), dismissalDao),
        BackupSourcesWriter(providerDao, indexerDao, debridDao, cipher),
        BackupPreferencesWriter(prefs, streams, usenetSettings, debridSettings),
        object : BackupTransactor {
            override suspend fun <T> inTransaction(block: suspend () -> T): T {
                transactions++
                return block()
            }
        },
        guard,
        io,
    )

    /** A profile with the default addons, as `ProfileRepository.create` makes it. */
    suspend fun addProfile(name: String, kids: Boolean = false): ProfileEntity {
        if (settings.currentApiKey().isNullOrBlank()) settings.setTmdbApiKey(TMDB_KEY)
        return profiles.create(com.seastreams.tv.data.profile.ProfileDraft(name = name, isKids = kids))
    }

    /** Usenet on, with one provider (password) and one indexer (API key), stored sealed. */
    suspend fun configureUsenet(password: String = "nntp-secret", apiKey: String = "indexer-key") {
        usenetSettings.setEnabled(true)
        providerDao.insert(
            UsenetProviderEntity(
                name = "Eweka", host = "news.eweka.nl", port = 563, tls = true, username = "ken", passwordCipher = cipher.encrypt(password),
                maxConnections = 20, pipelineDepth = 2, priority = 0, isBackup = false, enabled = true, sortOrder = 0,
            ),
        )
        indexerDao.insert(
            NewznabIndexerEntity(
                name = "NZBgeek", baseUrl = "https://api.nzbgeek.info", apiPath = "/api", apiKeyCipher = cipher.encrypt(apiKey),
                priority = 0, backupOnly = false, enabled = true, sortOrder = 0,
            ),
        )
    }

    /** Debrid on, with a Real-Debrid device sign-in stored sealed. */
    suspend fun configureDebrid(token: String = "rd-access-token", refresh: String = "rd-refresh-token") {
        debridSettings.setEnabled(true)
        debridDao.insert(
            DebridProviderEntity(
                providerType = "REAL_DEBRID", name = "Real-Debrid", authMethod = "DEVICE", credentialCipher = cipher.encrypt(token),
                refreshTokenCipher = cipher.encrypt(refresh), clientIdCipher = cipher.encrypt("rd-client-id"),
                clientSecretCipher = cipher.encrypt("rd-client-secret"), expiresAtMillis = 1_800_000_000_000,
                signInNeeded = false, enabled = true, sortOrder = 0,
            ),
        )
    }

    /** Saves [tmdbId] to [profileId]'s Library as of [addedAt]. */
    suspend fun save(
        profileId: Long, tmdbId: Int, title: String, addedAt: Long, certification: String? = "PG", type: MediaType = MediaType.MOVIE,
        keywordIds: String? = null,
    ) = libraryDao.insert(LibraryItemEntity(profileId, tmdbId, type, title, "/p$tmdbId.jpg", certification, "28,12", addedAt, keywordIds))

    /** Records playback of a movie (season/episode 0) or an episode. */
    suspend fun watch(
        profileId: Long, tmdbId: Int, positionMs: Long, updatedAt: Long, durationMs: Long = 100_000,
        season: Int = 0, episode: Int = 0, dismissed: Boolean = false,
    ) = progressDao.upsert(
        WatchProgressEntity(
            profileId, tmdbId, if (season == 0) MediaType.MOVIE else MediaType.TV, season, episode, "Title $tmdbId",
            if (season == 0) null else "Episode $episode", "/p$tmdbId.jpg", "/b$tmdbId.jpg", positionMs, durationMs, updatedAt, dismissed,
        ),
    )

    fun addonUrls(profileId: Long): List<String> = addonDao.ordered(profileId).map { it.manifestUrl }

    fun addon(profileId: Long, url: String): AddonEntity? = addonDao.rows.value[profileId to url]

    /** Installs a third-party addon directly (no network), at the bottom of [profileId]'s list. */
    suspend fun install(profileId: Long, url: String, name: String) = addonDao.upsert(
        AddonEntity(profileId, url, "org.$name", name, null, true, addonDao.nextSortOrder(profileId), """{"id":"org.$name","name":"$name"}""", 0),
    )

    companion object {
        const val TMDB_KEY = "tmdb-test-key-0123456789"
        val BUILT_IN_SOURCES = listOf(BuiltInAddons.USENET_URL, BuiltInAddons.DEBRID_URL)
    }
}
