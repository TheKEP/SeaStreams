package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.dao.AddonDao
import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.network.InvalidManifestException
import com.seastreams.tv.data.addon.BuiltInAddons
import com.seastreams.tv.core.network.StremioAddonClient
import com.seastreams.tv.core.network.StremioManifest
import com.seastreams.tv.core.network.StremioManifestParser
import com.seastreams.tv.core.network.StremioStream
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
 * parallel with a per-addon timeout, so one slow addon never holds up the list. The built-in `Usenet` addon (PRD §12)
 * is searched alongside and listed after the Stremio addons, only while at least one provider and one indexer are
 * enabled. Addon streams carrying an `nzbUrl` become opaque `seastreams-nntp://` streams, like the built-in ones.
 */
class ResolveStreamsUseCase @Inject constructor(
    private val addonDao: AddonDao,
    /** Lazy so the HTTP stack is built on IO. */
    private val client: Lazy<StremioAddonClient>,
    private val usenet: Lazy<UsenetStreamSearch>,
    private val usenetStreams: UsenetStreamRegistry,
    private val process: ProcessStreamsUseCase,
) {
    suspend operator fun invoke(query: StreamQuery): StreamResolution = withContext(Dispatchers.IO) {
        val type = query.ref.mediaType.stremioType
        val enabled = addonDao.observeEnabled(query.profileId).first()
        val addons = enabled.mapNotNull { entity -> entity.parsed()?.let { entity to it } }
        val usenet = UsenetSlot.of(enabled, addons.map { it.first }, disabled = enabled.none { it.manifestUrl == BuiltInAddons.USENET_URL } &&
            addonDao.getByUrl(query.profileId, BuiltInAddons.USENET_URL) != null)
        val (results, builtIn) = coroutineScope {
            val builtIn = async { if (usenet.disabled) null else queryUsenet(query) }
            addons.map { (entity, manifest) -> async { queryAddon(entity, manifest, type, query) } }.awaitAll() to builtIn.await()
        }
        // Usenet results take the built-in entry's place in the profile's addon order (the bottom when it has none yet).
        val all = results.toMutableList().apply { builtIn?.let { add(usenet.index.coerceAtMost(size), it) } }
        val cards = addons
            .filter { (_, manifest) -> stremioId(manifest, STREAM, type, query) != null }
            .map { (entity, _) -> StreamAddon(entity.manifestUrl, entity.displayName()) }
        val usenetCard = builtIn?.let { StreamAddon(UsenetStreamSearch.USENET_ADDON_URL, usenet.name) }
        val context = StreamContext(
            kind = query.contentKind,
            runtimeMinutes = query.runtimeMinutes,
            title = query.title,
            year = query.year,
            addonOrder = usenet.addonOrder,
        )
        StreamResolution(
            // Filters, dedup, sort, limits and formatter (PRD §13) run here, so every consumer sees the processed list.
            streams = process(query.profileId, all.flatMap { it.streams }, context),
            subtitles = results.flatMap { it.subtitles },
            failedAddons = all.filter { it.failed }.map { it.addonName },
            streamAddons = cards.toMutableList().apply {
                usenetCard?.let { add(usenet.cardIndex(addons.map { it.first }, cards).coerceAtMost(size), it) }
            },
        )
    }

    /**
     * Where the built-in `Usenet` entry sits among [enabled] addons: [index] counts the queried Stremio addons before
     * it, [addonOrder] is every enabled addon URL in order (Usenet last when the profile has no entry yet), and
     * [disabled] means the profile switched Usenet off (it isn't searched).
     */
    private class UsenetSlot(val index: Int, val addonOrder: List<String>, val name: String, val disabled: Boolean, private val row: Int) {

        /** The card's position among the stream-capable addon [cards]. */
        fun cardIndex(queried: List<AddonEntity>, cards: List<StreamAddon>): Int =
            if (row < 0) cards.size else queried.take(index).count { entity -> cards.any { it.url == entity.manifestUrl } }

        companion object {
            fun of(enabled: List<AddonEntity>, queried: List<AddonEntity>, disabled: Boolean): UsenetSlot {
                val row = enabled.indexOfFirst { it.manifestUrl == BuiltInAddons.USENET_URL }
                val before = if (row < 0) queried.size else enabled.take(row).count { it in queried }
                val urls = enabled.map { it.manifestUrl }
                return UsenetSlot(
                    index = before,
                    addonOrder = if (row < 0) urls + BuiltInAddons.USENET_URL else urls,
                    name = enabled.getOrNull(row)?.let { it.customName ?: it.name } ?: UsenetStreamSearch.USENET_ADDON_NAME,
                    disabled = disabled,
                    row = row,
                )
            }
        }
    }

    /** The built-in `Usenet` addon, or `null` (no card) when no provider or no indexer is enabled. */
    private suspend fun queryUsenet(query: StreamQuery): AddonResult? {
        val name = UsenetStreamSearch.USENET_ADDON_NAME
        return try {
            val search = usenet.get()
            if (!search.configured()) return null
            withTimeout(USENET_TIMEOUT_MILLIS) {
                val result = search.search(query.usenetTarget)
                AddonResult(name, streams = result.streams, failed = result.failed)
            }
        } catch (e: TimeoutCancellationException) {
            AddonResult(name, failed = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AddonResult(name, failed = true)
        }
    }

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

    private suspend fun queryAddon(entity: AddonEntity, manifest: StremioManifest, type: String, query: StreamQuery): AddonResult {
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
                        streams = streams.await().map { stream ->
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
