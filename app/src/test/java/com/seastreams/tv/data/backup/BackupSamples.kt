package com.seastreams.tv.data.backup

import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.datastore.StreamProcessingBackup
import com.seastreams.tv.data.addon.BuiltInAddons
import com.seastreams.tv.domain.streams.StreamProcessingSettings

/** Small, valid backup parts for the codec and plan tests. */
internal object BackupSamples {

    fun entry(name: String, kids: Boolean = false, addons: List<AddonBackup> = listOf(tmdb())) = ProfileBackupEntry(
        profile = ProfileMetaBackup(
            name = name,
            avatarIndex = 1,
            isKids = kids,
            movieCeiling = if (kids) "PG" else null,
            tvCeiling = if (kids) "TV_Y7" else null,
            allowUnrated = false,
        ),
        addons = addons,
        streams = StreamProcessingBackup.of(StreamProcessingSettings()),
    )

    fun tmdb() = AddonBackup(BuiltInAddons.TMDB_URL, "com.seastreams.tmdb", "TMDB Metadata", manifestJson = """{"id":"com.seastreams.tmdb"}""")

    fun addon(url: String, name: String, order: Int = 1) =
        AddonBackup(url, "org.$name", name, sortOrder = order, manifestJson = """{"id":"org.$name","name":"$name"}""")

    fun backup(scope: BackupScope, vararg entries: ProfileBackupEntry, usenet: UsenetBackup = UsenetBackup(), debrid: DebridBackup = DebridBackup()) =
        AppBackup(version = APP_BACKUP_VERSION, scope = scope.wire, profiles = entries.toList(), usenet = usenet, debrid = debrid)

    fun provider(password: String? = null) = NntpProviderBackup(
        name = "Eweka", host = "News.Eweka.NL", port = 563, tls = true, username = "ken", password = password,
        maxConnections = 20, pipelineDepth = 2, priority = 0,
    )

    fun indexer(apiKey: String? = null) =
        IndexerBackup(name = "NZBgeek", baseUrl = "https://api.nzbgeek.info", apiPath = "/api", apiKey = apiKey, priority = 0)

    fun profileRow(id: Long, name: String) =
        ProfileEntity(id = id, name = name, avatarIndex = 0, isKids = false, movieCeiling = null, tvCeiling = null, allowUnrated = false, createdAtMillis = id)
}
