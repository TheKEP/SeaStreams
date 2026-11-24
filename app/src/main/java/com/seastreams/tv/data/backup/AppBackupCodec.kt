package com.seastreams.tv.data.backup

import com.seastreams.tv.core.database.entity.LibrarySortOrder
import com.seastreams.tv.core.datastore.InvalidStreamBackupException
import com.seastreams.tv.core.datastore.StreamProcessingBackupCodec
import com.seastreams.tv.core.network.StremioAddonUrl
import com.seastreams.tv.core.util.enumByNameOrNull
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.addon.AddonRepository
import com.seastreams.tv.data.addon.BuiltInAddons
import com.seastreams.tv.data.debrid.DebridAuthMethod
import com.seastreams.tv.data.debrid.DebridServiceKind
import com.seastreams.tv.data.debrid.DebridSourceValidation
import com.seastreams.tv.data.profile.ProfileDraft
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.data.profile.ProfileValidation
import com.seastreams.tv.data.usenet.IndexerDraft
import com.seastreams.tv.data.usenet.ProviderDraft
import com.seastreams.tv.data.usenet.UsenetSourceValidation
import com.seastreams.tv.data.usenet.headers.HeaderPreset
import com.seastreams.tv.domain.model.MovieCertification
import com.seastreams.tv.domain.model.TvCertification
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/** A backup that can't be restored; [message] says why, in words the portal can show. Nothing was written. */
class InvalidAppBackupException(message: String) : IllegalArgumentException(message)

/**
 * Encodes backups for download, and decodes, validates and normalises uploaded ones (ARCHITECTURE §13). [decode] is
 * pure: it reads nothing and writes nothing, so a rejected file can never leave a partial restore behind. Numbers with a
 * range (stream limits, Usenet performance, playback values when written) are clamped; anything structurally wrong is
 * refused.
 */
object AppBackupCodec {

    const val MAX_MANIFEST_CHARS = 256 * 1024
    private const val MAX_SECRET_CHARS = 4096

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    fun encode(backup: AppBackup): String = json.encodeToString(AppBackup.serializer(), backup)

    fun decode(text: String): AppBackup {
        val raw = try {
            json.decodeFromString(AppBackup.serializer(), text)
        } catch (e: SerializationException) {
            invalid("That isn't a seastreams backup file")
        } catch (e: IllegalArgumentException) {
            invalid("That isn't a seastreams backup file")
        }
        if (raw.version != APP_BACKUP_VERSION) invalid("Unsupported backup file version ${raw.version}")
        val scope = BackupScope.fromWire(raw.scope) ?: invalid("Unknown backup type “${raw.scope.take(20)}”")
        val profiles = raw.profiles
        when {
            profiles.isEmpty() -> invalid("The backup holds no profiles")
            scope == BackupScope.PROFILE && profiles.size != 1 -> invalid("A profile backup must hold exactly one profile")
            profiles.size > ProfileRepository.MAX_PROFILES -> invalid("A backup can hold at most ${ProfileRepository.MAX_PROFILES} profiles")
        }
        val checked = profiles.mapIndexed { index, entry -> checkedProfile(index, entry) }
        if (scope == BackupScope.APP) {
            val names = checked.map { it.profile.name.lowercase(Locale.ROOT) }
            if (names.toSet().size != names.size) invalid("Two profiles in the backup share a name")
        }
        return raw.copy(
            tmdbApiKey = raw.tmdbApiKey?.let(::checkedTmdbKey),
            usenet = checkedUsenet(raw.usenet),
            debrid = checkedDebrid(raw.debrid),
            profiles = checked,
        )
    }

    /** [meta] as validated profile fields (the same rules as the TV form and the portal). */
    fun draftOf(meta: ProfileMetaBackup): ProfileDraft {
        val movie = enumByNameOrNull<MovieCertification>(meta.movieCeiling)
        val tv = enumByNameOrNull<TvCertification>(meta.tvCeiling)
        if (meta.isKids && (movie == null || tv == null)) invalid("Profile “${meta.name.take(20)}” has no valid Kids ratings")
        val draft = ProfileDraft(
            name = meta.name,
            avatarIndex = meta.avatarIndex,
            isKids = meta.isKids,
            movieCeiling = movie ?: ProfileDraft.DEFAULT_MOVIE_CEILING,
            tvCeiling = tv ?: ProfileDraft.DEFAULT_TV_CEILING,
            allowUnrated = meta.allowUnrated,
        )
        return try {
            ProfileValidation.validated(draft)
        } catch (e: UserFacingException) {
            invalid("Profile “${meta.name.take(20)}”: ${e.message}")
        }
    }

    fun sortOrderOf(meta: ProfileMetaBackup): LibrarySortOrder =
        enumByNameOrNull<LibrarySortOrder>(meta.librarySortOrder) ?: LibrarySortOrder.A_TO_Z

    private fun checkedProfile(index: Int, entry: ProfileBackupEntry): ProfileBackupEntry {
        val draft = draftOf(entry.profile)
        val label = "Profile ${index + 1} (${draft.name})"
        val streams = try {
            StreamProcessingBackupCodec.validated(entry.streams)
        } catch (e: InvalidStreamBackupException) {
            invalid("$label: ${e.message}")
        }
        val addons = entry.addons.map { checkedAddon(label, it) }
        if (addons.map { it.manifestUrl }.toSet().size != addons.size) invalid("$label lists the same addon twice")
        HistoryBackupValidation.check(label, entry)
        return entry.copy(profile = entry.profile.copy(name = draft.name), addons = addons, streams = streams)
    }

    private fun checkedAddon(label: String, addon: AddonBackup): AddonBackup {
        val url = if (BuiltInAddons.isProtected(addon.manifestUrl)) {
            addon.manifestUrl
        } else {
            StremioAddonUrl.normalize(addon.manifestUrl) ?: invalid("$label: “${addon.manifestUrl.take(80)}” isn't an addon URL")
        }
        if (addon.manifestId.isBlank() || addon.name.isBlank()) invalid("$label: an addon has no id or name")
        if (addon.manifestJson.length > MAX_MANIFEST_CHARS) invalid("$label: the manifest of ${addon.name.take(40)} is too large")
        val manifestIsObject = try {
            json.parseToJsonElement(addon.manifestJson) is JsonObject
        } catch (e: SerializationException) {
            false
        }
        if (!manifestIsObject) invalid("$label: the manifest of ${addon.name.take(40)} isn't valid JSON")
        val customName = addon.customName?.trim()?.takeIf { it.isNotEmpty() }
        if ((customName?.length ?: 0) > AddonRepository.MAX_NAME_LENGTH) invalid("$label: an addon name is too long")
        // Only TMDB can't be switched off (PRD §8).
        val enabled = addon.enabled || !BuiltInAddons.canDisable(url)
        return addon.copy(manifestUrl = url, customName = customName, enabled = enabled)
    }

    private fun checkedUsenet(usenet: UsenetBackup): UsenetBackup {
        val providers = usenet.providers.map { p ->
            val valid = userFacing {
                UsenetSourceValidation.provider(
                    ProviderDraft(p.name, p.host, p.port, p.tls, p.username, p.password, p.maxConnections, p.pipelineDepth, p.priority, p.isBackup, p.enabled),
                )
            }
            p.copy(name = valid.name, host = valid.host, username = valid.username, password = p.password?.takeIf { it.isNotEmpty() })
        }
        if (providers.map { it.matchKey() }.toSet().size != providers.size) invalid("Two Usenet providers in the backup use the same server and username")
        val indexers = usenet.indexers.map { i ->
            val valid = userFacing {
                UsenetSourceValidation.indexer(IndexerDraft(i.name, i.baseUrl, i.apiPath, i.apiKey, i.priority, i.backupOnly, i.enabled))
            }
            i.copy(name = valid.name, baseUrl = valid.baseUrl, apiPath = valid.apiPath, apiKey = valid.apiKey?.takeIf { it.isNotEmpty() })
        }
        if (indexers.map { it.matchKey() }.toSet().size != indexers.size) invalid("Two indexers in the backup use the same address")
        return usenet.copy(
            performance = usenet.performance.clamped(),
            searchHeaders = usenet.searchHeaders?.takeIf { enumByNameOrNull<HeaderPreset>(it) != null },
            downloadHeaders = usenet.downloadHeaders?.takeIf { enumByNameOrNull<HeaderPreset>(it) != null },
            providers = providers,
            indexers = indexers,
        )
    }

    private fun checkedDebrid(debrid: DebridBackup): DebridBackup {
        val providers = debrid.providers.map { p ->
            val kind = DebridServiceKind.fromName(p.providerType) ?: invalid("Unknown Debrid service “${p.providerType.take(30)}”")
            if (enumByNameOrNull<DebridAuthMethod>(p.authMethod) == null) invalid("${kind.displayName}: unknown sign-in method")
            val name = userFacing { DebridSourceValidation.name(kind, p.name) }
            val credential = p.credential?.takeIf { it.isNotBlank() }?.let { userFacing { DebridSourceValidation.key(kind, it) } }
            p.copy(
                name = name,
                credential = credential,
                refreshToken = p.refreshToken?.let { secret(kind.displayName, it) },
                clientId = p.clientId?.let { secret(kind.displayName, it) },
                clientSecret = p.clientSecret?.let { secret(kind.displayName, it) },
            )
        }
        if (providers.map { it.providerType }.toSet().size != providers.size) invalid("The backup lists a Debrid service twice")
        return debrid.copy(providers = providers)
    }

    private fun checkedTmdbKey(raw: String): String? {
        val key = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        if (key.length > 256 || key.any { it.isWhitespace() || it.isISOControl() }) invalid("The TMDB API key in the backup isn't valid")
        return key
    }

    private fun secret(owner: String, raw: String): String? {
        val value = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        if (value.length > MAX_SECRET_CHARS || value.any { it.isWhitespace() || it.isISOControl() }) invalid("$owner: a stored token isn't valid")
        return value
    }

    private inline fun <T> userFacing(block: () -> T): T = try {
        block()
    } catch (e: UserFacingException) {
        invalid(e.message.orEmpty())
    }

    private fun invalid(message: String): Nothing = throw InvalidAppBackupException(message)
}

/** How a restored provider finds the TV's existing one: server (case-insensitive), port and username. */
internal fun NntpProviderBackup.matchKey() = Triple(host.lowercase(Locale.ROOT), port, username)

/** How a restored indexer finds the TV's existing one: its normalised address and API path. */
internal fun IndexerBackup.matchKey() = baseUrl.lowercase(Locale.ROOT).trimEnd('/') to apiPath
