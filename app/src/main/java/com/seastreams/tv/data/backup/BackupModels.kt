package com.seastreams.tv.data.backup

import com.seastreams.tv.core.database.entity.LibrarySortOrder
import com.seastreams.tv.core.datastore.StreamProcessingBackup
import com.seastreams.tv.core.util.redacted
import com.seastreams.tv.domain.usenet.UsenetPerformance
import kotlinx.serialization.Serializable

/** The only backup layout so far; a file with any other `version` is refused. */
const val APP_BACKUP_VERSION = 1

/** What a backup holds and how it restores (PRD §8 *Backup & restore*). */
enum class BackupScope(val wire: String) {
    /** One profile, restored onto the profile picked in the portal. */
    PROFILE("profile"),

    /** Every profile, matched to the TV's profiles by name. */
    APP("app"),
    ;

    companion object {
        fun fromWire(value: String?): BackupScope? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * A seastreams backup file (ARCHITECTURE §13). Both scopes carry the app-wide Usenet / Debrid block, so either restores a
 * working setup. Secrets ([tmdbApiKey], provider passwords, indexer API keys, Debrid keys and tokens) are present only
 * when the file was exported with *Include credentials*; otherwise they are `null` and left out of the JSON.
 */
@Serializable
data class AppBackup(
    val version: Int,
    val scope: String,
    val createdAtMillis: Long = 0,
    val includesCredentials: Boolean = false,
    val tmdbApiKey: String? = null,
    val usenet: UsenetBackup = UsenetBackup(),
    val debrid: DebridBackup = DebridBackup(),
    val profiles: List<ProfileBackupEntry>,
) {
    override fun toString(): String =
        "AppBackup(v$version, $scope, credentials=$includesCredentials, tmdbApiKey=${redacted(tmdbApiKey)}, profiles=${profiles.size})"
}

@Serializable
data class UsenetBackup(
    val enabled: Boolean = false,
    val performance: UsenetPerformance = UsenetPerformance(),
    /** `HeaderPreset` names; an unknown name restores the default preset. */
    val searchHeaders: String? = null,
    val downloadHeaders: String? = null,
    val providers: List<NntpProviderBackup> = emptyList(),
    val indexers: List<IndexerBackup> = emptyList(),
)

@Serializable
data class NntpProviderBackup(
    val name: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val username: String = "",
    val password: String? = null,
    val maxConnections: Int,
    val pipelineDepth: Int,
    val priority: Int,
    val isBackup: Boolean = false,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
) {
    override fun toString(): String = "NntpProviderBackup($name, $host:$port, tls=$tls, password=${redacted(password)})"
}

@Serializable
data class IndexerBackup(
    val name: String,
    val baseUrl: String,
    val apiPath: String,
    val apiKey: String? = null,
    val priority: Int,
    val backupOnly: Boolean = false,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
) {
    override fun toString(): String = "IndexerBackup($name, $baseUrl, apiKey=${redacted(apiKey)})"
}

@Serializable
data class DebridBackup(
    val enabled: Boolean = false,
    val providers: List<DebridProviderBackup> = emptyList(),
)

@Serializable
data class DebridProviderBackup(
    /** `DebridServiceKind` name, one entry per service. */
    val providerType: String,
    val name: String,
    /** `DebridAuthMethod` name. */
    val authMethod: String,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val credential: String? = null,
    val refreshToken: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val expiresAtMillis: Long? = null,
) {
    override fun toString(): String = "DebridProviderBackup($providerType, $name, $authMethod, credential=${redacted(credential)}, " +
        "refreshToken=${redacted(refreshToken)}, clientId=${redacted(clientId)}, clientSecret=${redacted(clientSecret)})"
}

/** One profile: its fields, General Settings, addon list and stream processing. */
@Serializable
data class ProfileBackupEntry(
    val profile: ProfileMetaBackup,
    val settings: GeneralSettingsBackup = GeneralSettingsBackup(),
    val addons: List<AddonBackup> = emptyList(),
    val streams: StreamProcessingBackup,
    /** Saved titles (incl. MDBList imports). Absent in files from before Library backups: restores nothing. */
    val library: List<LibraryItemBackup> = emptyList(),
    /** Resume points, watched state and Continue Watching removals. Absent in older files: restores nothing. */
    val watchProgress: List<WatchProgressBackup> = emptyList(),
    /** Removed Next Up cards. Absent in files from before Milestone 13: restores nothing. */
    val nextUpDismissals: List<NextUpDismissalBackup> = emptyList(),
)

@Serializable
data class ProfileMetaBackup(
    val name: String,
    val avatarIndex: Int = 0,
    val isKids: Boolean = false,
    /** `MovieCertification` / `TvCertification` names; only meaningful on Kids profiles. */
    val movieCeiling: String? = null,
    val tvCeiling: String? = null,
    val allowUnrated: Boolean = false,
    val librarySortOrder: String = LibrarySortOrder.A_TO_Z.name,
)

/**
 * An installed addon with its manifest, so a restore needs no network. Built-in entries (`builtin://tmdb`,
 * `usenet://builtin`, `debrid://builtin`) are listed like any other.
 */
@Serializable
data class AddonBackup(
    val manifestUrl: String,
    val manifestId: String,
    val name: String,
    val customName: String? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val manifestJson: String,
)
