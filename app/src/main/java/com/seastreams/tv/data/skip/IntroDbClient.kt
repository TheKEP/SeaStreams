package com.seastreams.tv.data.skip

import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.core.util.IoDispatcher
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.playback.skip.SegmentKind
import com.seastreams.tv.domain.playback.skip.SegmentSource
import com.seastreams.tv.domain.playback.skip.SkipSegment
import dagger.Lazy
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import kotlin.math.roundToLong

/** IntroDB's `/segments` answer; a segment nobody has timed yet is `null`. */
@Serializable
data class IntroDbSegmentsDto(
    val intro: IntroDbSegmentDto? = null,
    val recap: IntroDbSegmentDto? = null,
    val outro: IntroDbSegmentDto? = null,
    @SerialName("post_credits") val postCredits: IntroDbSegmentDto? = null,
) {
    fun toSegments(): List<SkipSegment> = listOfNotNull(
        intro?.toSegment(SegmentKind.INTRO),
        recap?.toSegment(SegmentKind.RECAP),
        outro?.toSegment(SegmentKind.CREDITS),
        postCredits?.toSegment(SegmentKind.POST_CREDITS),
    )
}

/** One timed segment; milliseconds win over seconds when both are sent. */
@Serializable
data class IntroDbSegmentDto(
    @SerialName("start_sec") val startSec: Double? = null,
    @SerialName("end_sec") val endSec: Double? = null,
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
) {
    fun toSegment(kind: SegmentKind): SkipSegment? {
        val start = startMs ?: startSec?.toMillis() ?: return null
        val end = endMs ?: endSec?.toMillis() ?: return null
        if (start < 0 || end <= start) return null
        return SkipSegment(kind, start, end, SegmentSource.INTRODB)
    }

    private fun Double.toMillis(): Long? = takeIf { it.isFinite() }?.let { (it * MILLIS_PER_SECOND).roundToLong() }
}

/** File level: a companion object inside a `@Serializable` class breaks reflective serializer lookup. */
private const val MILLIS_PER_SECOND = 1_000.0

/**
 * IntroDB reads (`GET /segments?imdb_id=…[&season=&episode=|&is_movie=true]`, no key needed). Sends only the
 * title's IMDb id and season/episode, already known for the single title being played (CLAUDE.md rule 5: no
 * extra TMDB calls). Any failure (HTTP error, timeout, bad JSON, no network) is `null`, so playback never waits
 * on it or shows an error.
 */
class IntroDbClient @Inject constructor(
    private val client: Lazy<HttpClient>,
    private val endpoints: ApiEndpoints,
    private val json: Json,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun segments(imdbId: String, episode: EpisodeRef?): List<SkipSegment>? {
        if (!IMDB_ID.matches(imdbId)) return null
        return try {
            withContext(io) {
                withTimeout(TIMEOUT_MS) {
                    val response = client.get().get("${endpoints.introDbBaseUrl}/segments") {
                        parameter("imdb_id", imdbId)
                        if (episode == null) {
                            parameter("is_movie", true)
                        } else {
                            parameter("season", episode.season)
                            parameter("episode", episode.episode)
                        }
                    }
                    if (!response.status.isSuccess()) return@withTimeout null
                    json.decodeFromString(IntroDbSegmentsDto.serializer(), response.bodyAsText()).toSegments()
                }
            }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        val IMDB_ID = Regex("^tt\\d+$")
        const val TIMEOUT_MS = 4_000L
    }
}
