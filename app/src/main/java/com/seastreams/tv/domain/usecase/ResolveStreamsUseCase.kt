package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.dao.AddonDao
import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.network.InvalidManifestException
import com.seastreams.tv.core.network.StremioAddonClient
import com.seastreams.tv.core.network.StremioManifest
import com.seastreams.tv.core.network.StremioManifestParser
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.StreamAddon
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.model.SubtitleTrack
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
 * [imdbId] is used where addons accept it, else the `tmdb:` id.
 */
data class StreamQuery(val profileId: Long, val ref: MediaRef, val imdbId: String?, val episode: EpisodeRef?)

/**
 * Queries every Stremio addon the profile has enabled that serves `stream` or `subtitles` for the title's type, all in
 * parallel with a per-addon timeout, so one slow addon never holds up the list.
 */
class ResolveStreamsUseCase @Inject constructor(
    private val addonDao: AddonDao,
    /** Lazy so the HTTP stack is built on IO. */
    private val client: Lazy<StremioAddonClient>,
) {
    suspend operator fun invoke(query: StreamQuery): StreamResolution = withContext(Dispatchers.IO) {
        val type = query.ref.mediaType.stremioType
        val addons = addonDao.observeEnabled(query.profileId).first().mapNotNull { entity -> entity.parsed()?.let { entity to it } }
        val results = coroutineScope {
            addons.map { (entity, manifest) -> async { queryAddon(entity, manifest, type, query) } }.awaitAll()
        }
        StreamResolution(
            streams = results.flatMap { it.streams },
            subtitles = results.flatMap { it.subtitles },
            failedAddons = results.filter { it.failed }.map { it.addonName },
            streamAddons = addons
                .filter { (_, manifest) -> stremioId(manifest, STREAM, type, query) != null }
                .map { (entity, _) -> StreamAddon(entity.manifestUrl, entity.displayName()) },
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
                                url = stream.url,
                                label = stream.name.lines().filter { it.isNotBlank() }.joinToString(" · ").ifBlank { name },
                                description = stream.description,
                                headers = stream.headers,
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
