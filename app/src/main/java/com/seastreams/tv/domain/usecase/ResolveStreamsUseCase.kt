package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.dao.AddonDao
import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.core.network.InvalidManifestException
import com.seastreams.tv.data.addon.BuiltInAddons
import com.seastreams.tv.core.network.StremioAddonClient
import com.seastreams.tv.core.network.StremioManifest
import com.seastreams.tv.core.network.StremioManifestParser
import com.seastreams.tv.core.network.StremioStream
import com.seastreams.tv.data.debrid.library.DebridLibrarySearch
import com.seastreams.tv.data.usenet.newznab.NzbRequest
import com.seastreams.tv.data.usenet.newznab.UsenetSearchTarget
import com.seastreams.tv.data.usenet.newznab.UsenetStreamSearch
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamAddon
import com.seastreams.tv.domain.model.StreamHints
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.model.SubtitleTrack
import com.seastreams.tv.domain.streams.ContentKind
import com.seastreams.tv.domain.streams.ProcessStreamsUseCase
import com.seastreams.tv.domain.streams.StreamContext
import com.seastreams.tv.usenet.session.AddonServers
import com.seastreams.tv.usenet.session.UsenetGrab
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale
import javax.inject.Inject

/** What every enabled addon returned for one movie or episode. */
data class StreamResolution(
    /** Grouped by addon (in the user's addon order), each addon's own order kept. */
    val streams: List<StreamSource>,
    val subtitles: List<SubtitleTrack>,
    /** Display names of addons that failed or timed out. */
    val failedAddons: List<String>,
    /**
     * Every enabled addon that serves `stream` for this title's type and accepts its id, in the
     * user's addon order, including ones that returned nothing or failed (the stream panel's addon cards).
     */
    val streamAddons: List<StreamAddon> = emptyList(),
)

/**
 * Which title to resolve, with whose addons: only [profileId]'s enabled addons are queried.
 * [imdbId] is used where addons accept it, else the `tmdb:` id. [title] / [year] feed the Usenet `q=` fallback.
 */
data class StreamQuery(
    val profileId: Long,
    val ref: MediaRef,
    val imdbId: String?,
    val episode: EpisodeRef?,
    val title: String? = null,
    val year: Int? = null,
    /** TMDB runtime (the episode's for shows): stream bitrate is estimated as size ÷ runtime (PRD §13). */
    val runtimeMinutes: Int? = null,
    /** Japanese animation: the `Anime` sort list applies. */
    val isAnime: Boolean = false,
) {
    /** Which stream sort list and size limits apply. */
    val contentKind: ContentKind
        get() = when {
            isAnime -> ContentKind.ANIME
            ref.mediaType == MediaType.MOVIE -> ContentKind.MOVIE
            else -> ContentKind.SERIES
        }

    /** What the built-in `Usenet` addon searches for. */
    val usenetTarget: UsenetSearchTarget
        get() = episode?.let { UsenetSearchTarget.Episode(imdbId, title, it.season, it.episode) }
            ?: UsenetSearchTarget.Movie(imdbId, title, year)
}

/**
 * Queries every Stremio addon the profile has enabled that serves `stream` or `subtitles` for the title's type, all in
 * parallel with a per-addon timeout, so one slow addon never holds up the list. The built-in `Usenet` (PRD §12) and
 * `Debrid` (PRD §14) sources are searched alongside, each only while it is configured and the profile hasn't switched
 * its entry off, and listed at their entries' places ([BuiltInSlot]). Addon streams carrying an `nzbUrl` become opaque
 * `seastreams-nntp://` streams, like the built-in ones.
 */
class ResolveStreamsUseCase @Inject constructor(
    private val addonDao: AddonDao,
    /** Lazy so the HTTP stack is built on IO. */
    private val client: Lazy<StremioAddonClient>,
    private val usenet: Lazy<UsenetStreamSearch>,
    private val usenetStreams: UsenetStreamRegistry,
    private val process: ProcessStreamsUseCase,
    private val usenetSettings: UsenetSettingsStore,
    private val debrid: Lazy<DebridLibrarySearch>,
) {
    suspend operator fun invoke(query: StreamQuery): StreamResolution = withContext(Dispatchers.IO) {
        val type = query.ref.mediaType.stremioType
        val enabled = addonDao.observeEnabled(query.profileId).first()
        val addons = enabled.mapNotNull { entity -> entity.parsed()?.let { entity to it } }
        // While `Enable Usenet` is OFF, addon `nzbUrl` streams that need the user's providers are dropped (PRD §12).
        val usenetOn = usenetSettings.enabled.first()
        val usenetSlot = slot(query, enabled, BuiltInAddons.USENET_URL, UsenetStreamSearch.USENET_ADDON_NAME)
        val debridSlot = slot(query, enabled, BuiltInAddons.DEBRID_URL, DebridLibrarySearch.DEBRID_ADDON_NAME)
        val (results, builtIns) = coroutineScope {
            val usenetResult = async { if (usenetSlot.disabled) null else queryUsenet(query, usenetSlot.name) }
            val debridResult = async { if (debridSlot.disabled) null else queryDebrid(query, debridSlot.name) }
            val stremio = addons.map { (entity, manifest) -> async { entity.manifestUrl to queryAddon(entity, manifest, type, query, usenetOn) } }
            stremio.awaitAll() to listOfNotNull(
                usenetResult.await()?.let { usenetSlot to it },
                debridResult.await()?.let { debridSlot to it },
            )
        }
        // Built-in results take their entry's place in the profile's addon order (the bottom when it has none yet).
        val order = streamOrder(enabled, builtIns.map { it.first })
        val byUrl = results.toMap() + builtIns.associate { (slot, result) -> slot.url to result }
        val all = order.mapNotNull { byUrl[it] }
        val cardsByUrl = addons
            .filter { (_, manifest) -> stremioId(manifest, STREAM, type, query) != null }
            .associate { (entity, _) -> entity.manifestUrl to StreamAddon(entity.manifestUrl, entity.displayName()) } +
            builtIns.associate { (slot, _) -> slot.url to StreamAddon(slot.url, slot.name) }
        val context = StreamContext(
            kind = query.contentKind,
            runtimeMinutes = query.runtimeMinutes,
            title = query.title,
            year = query.year,
            addonOrder = order,
        )
        StreamResolution(
            // Filters, dedup, sort, limits and formatter (PRD §13) run here, so every consumer sees the processed list.
            streams = process(query.profileId, all.flatMap { it.streams }, context),
            subtitles = results.flatMap { it.second.subtitles },
            failedAddons = all.filter { it.failed }.map { it.addonName },
            streamAddons = order.mapNotNull { cardsByUrl[it] },
        )
    }

    /** [url]'s place and name on this profile; a disabled row means the profile switched that source off. */
    private suspend fun slot(query: StreamQuery, enabled: List<AddonEntity>, url: String, name: String): BuiltInSlot =
        BuiltInSlot.of(url, name, enabled, exists = enabled.any { it.manifestUrl == url } || addonDao.getByUrl(query.profileId, url) != null)

    /** The built-in `Debrid` addon (PRD §14), or `null` (no card) when Debrid is off or no provider is usable. */
    private suspend fun queryDebrid(query: StreamQuery, name: String): AddonResult? =
        queryBuiltIn(name, DEBRID_TIMEOUT_MILLIS, { debrid.get().configured() }) {
            debrid.get().search(query.usenetTarget).let { AddonResult(name, streams = it.streams, failed = it.failed) }
        }

    /** The built-in `Usenet` addon (PRD §12), or `null` (no card) when no provider or no indexer is enabled. */
    private suspend fun queryUsenet(query: StreamQuery, name: String): AddonResult? =
        queryBuiltIn(name, USENET_TIMEOUT_MILLIS, { usenet.get().configured() }) {
            usenet.get().search(query.usenetTarget).let { AddonResult(name, streams = it.streams, failed = it.failed) }
        }

    /** A built-in source: `null` while not [configured]; a timeout or failure is a failed card, never a thrown error. */
    private suspend fun queryBuiltIn(
        name: String,
        timeoutMillis: Long,
        configured: suspend () -> Boolean,
        search: suspend () -> AddonResult,
    ): AddonResult? = try {
        if (!configured()) null else withTimeout(timeoutMillis) { search() }
    } catch (e: TimeoutCancellationException) {
        AddonResult(name, failed = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AddonResult(name, failed = true)
    }

    /** An `nzbUrl` stream without its own `servers`: it would play through the user's providers. */
    private fun needsOwnProviders(stream: StremioStream): Boolean =
        stream.nzbUrl != null && AddonServers.parse(stream.servers).isEmpty()

    /** An addon's `nzbUrl` stream, registered so its link (and any `servers` logins) stay out of the URL. */
    private fun usenetUrl(entity: AddonEntity, stream: StremioStream): String {
        val nzbUrl = stream.nzbUrl ?: return stream.url
        val title = stream.description.lineSequence().firstOrNull { it.isNotBlank() } ?: stream.name
        return usenetStreams.register(
            UsenetGrab.FromAddon(
                addonUrl = entity.manifestUrl,
                nzb = NzbRequest(nzbUrl, cacheKey = "addon:" + entity.manifestUrl + ":" + nzbUrl, headers = stream.headers),
                title = title,
                servers = AddonServers.parse(stream.servers),
            ),
        )
    }

    private suspend fun queryAddon(
        entity: AddonEntity,
        manifest: StremioManifest,
        type: String,
        query: StreamQuery,
        usenetOn: Boolean,
    ): AddonResult {
        val name = entity.displayName()
        val streamId = stremioId(manifest, STREAM, type, query)
        val subtitleId = stremioId(manifest, SUBTITLES, type, query)
        if (streamId == null && subtitleId == null) return AddonResult(name)
        return try {
            withTimeout(ADDON_TIMEOUT_MILLIS) {
                coroutineScope {
                    val streams = async {
                        streamId?.let { client.get().fetchStreams(entity.manifestUrl, type, it) }.orEmpty()
                    }
                    val subtitles = async {
                        subtitleId?.let { client.get().fetchSubtitles(entity.manifestUrl, type, it) }.orEmpty()
                    }
                    AddonResult(
                        addonName = name,
                        streams = streams.await().filter { usenetOn || !needsOwnProviders(it) }.map { stream ->
                            StreamSource(
                                addonUrl = entity.manifestUrl,
                                addonName = name,
                                url = usenetUrl(entity, stream),
                                label = stream.name.lines().filter { it.isNotBlank() }.joinToString(" · ").ifBlank { name },
                                description = stream.description,
                                headers = if (stream.nzbUrl == null) stream.headers else emptyMap(),
                                hints = StreamHints(filename = stream.filename, sizeBytes = stream.videoSize),
                            )
                        },
                        subtitles = subtitles.await().map { SubtitleTrack(it.url, it.language, name) },
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            AddonResult(name, failed = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AddonResult(name, failed = true)
        }
    }

    private fun AddonEntity.displayName(): String = customName ?: name

    private fun AddonEntity.parsed(): StremioManifest? {
        if (!manifestUrl.startsWith("http")) return null // built-in providers are served in-process
        return try {
            StremioManifestParser.parse(manifestJson)
        } catch (e: InvalidManifestException) {
            null
        }
    }

    private class AddonResult(
        val addonName: String,
        val streams: List<StreamSource> = emptyList(),
        val subtitles: List<SubtitleTrack> = emptyList(),
        val failed: Boolean = false,
    )

    companion object {
        const val ADDON_TIMEOUT_MILLIS = 15_000L

        /** Caps, an id search and maybe a `q=` fallback per indexer: a little longer than one addon call. */
        const val USENET_TIMEOUT_MILLIS = 20_000L

        /** Each provider has [DebridLibrarySearch.TIMEOUT_MILLIS]; this only bounds a search that hangs as a whole. */
        const val DEBRID_TIMEOUT_MILLIS = DebridLibrarySearch.TIMEOUT_MILLIS + 5_000L
        private const val STREAM = "stream"
        private const val SUBTITLES = "subtitles"
    }
}

/**
 * The id [manifest] understands for [resource]: the IMDb id when the addon accepts `tt` ids (or
 * declares no prefixes), else `tmdb:{id}` when it accepts that. Episodes append `:{season}:{episode}`.
 * `null` when the addon doesn't serve [resource] for [type] or accepts neither id.
 */
internal fun stremioId(manifest: StremioManifest, resource: String, type: String, query: StreamQuery): String? {
    val declared = manifest.resourceFor(resource, type) ?: return null
    val prefixes = declared.idPrefixes.ifEmpty { manifest.idPrefixes }
    val imdb = query.imdbId
    val tmdb = "tmdb:${query.ref.tmdbId}"
    val base = when {
        prefixes.isEmpty() -> imdb
        imdb != null && prefixes.any { imdb.startsWith(it) } -> imdb
        prefixes.any { tmdb.startsWith(it) } -> tmdb
        else -> null
    } ?: return null
    val episode = query.episode ?: return base
    return "$base:${episode.season}:${episode.episode}"
}

/** The stream auto-play starts: the preferred addon's first stream, else the first stream overall. */
fun topStream(streams: List<StreamSource>, preferredAddonUrl: String?): StreamSource? =
    streams.firstOrNull { preferredAddonUrl != null && it.addonUrl == preferredAddonUrl } ?: streams.firstOrNull()

/** Whether a Stremio subtitle language (ISO 639-2, e.g. `eng`, or 639-1) is [preferred] (639-1, e.g. `en`). */
fun subtitleLanguageMatches(language: String, preferred: String): Boolean {
    val lang = language.trim().lowercase(Locale.ROOT)
    val pref = preferred.trim().lowercase(Locale.ROOT)
    if (lang.isEmpty() || pref.isEmpty()) return false
    if (lang == pref) return true
    val prefIso3 = runCatching { Locale.forLanguageTag(pref).isO3Language }.getOrDefault("")
    // ISO 639-2/B codes some addons use for languages whose 639-2/T code Java reports.
    val bibliographic = mapOf("fra" to "fre", "deu" to "ger", "zho" to "chi", "nld" to "dut", "ces" to "cze", "ell" to "gre")
    return lang == prefIso3 || lang == bibliographic[prefIso3]
}
