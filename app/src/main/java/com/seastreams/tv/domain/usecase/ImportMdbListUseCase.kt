package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.dao.LibraryDao
import com.seastreams.tv.core.database.dao.ProfileDao
import com.seastreams.tv.core.database.entity.LibraryItemEntity
import com.seastreams.tv.core.network.MdbListApiService
import com.seastreams.tv.core.network.MdbListItem
import com.seastreams.tv.core.network.MdbListUrl
import com.seastreams.tv.core.network.TmdbApiKeyMissingException
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.model.MediaType
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.time.Clock
import javax.inject.Inject

/** Which Library section an imported MDBList fills. */
enum class LibraryTarget(val mediaType: MediaType) { MOVIES(MediaType.MOVIE), TV_SHOWS(MediaType.TV) }

data class LibraryImportResult(
    val added: Int,
    val alreadyInLibrary: Int,
    /** Entries of the other media type (e.g. shows in a list imported into Movies). */
    val skippedOtherType: Int,
    /** Entries stripped by the profile's age restriction (Kids profiles only). */
    val blockedByAgeRestriction: Int,
    /** Entries whose TMDB details could not be loaded. */
    val failed: Int,
)

/**
 * Imports an MDBList list into a profile's Movies or TV Shows Library, once (PRD §8).
 *
 * This is a strictly one-time batch copy: the list is fetched once, its titles are inserted into
 * [LibraryDao] (duplicates ignored) and nothing about the list itself — URL, title, contents — is
 * stored anywhere, so SeaStreams never tracks or re-syncs it afterwards.
 *
 * MDBList exports carry no posters or certifications, so each entry's TMDB details are loaded
 * (under [MediaHydrator]'s app-wide concurrency gate, HTTP-cached for 12h). Age restrictions are
 * enforced here, before anything is written, so Kids libraries never contain out-of-range titles
 * (CLAUDE.md rule 6). No `/external_ids` calls are made.
 */
class ImportMdbListUseCase @Inject constructor(
    private val mdbListApi: MdbListApiService,
    private val hydrator: MediaHydrator,
    private val profileDao: ProfileDao,
    private val libraryDao: LibraryDao,
    private val ageEngine: AgeRestrictionEngine,
    private val clock: Clock,
) {
    suspend operator fun invoke(profileId: Long, rawUrl: String, target: LibraryTarget): LibraryImportResult {
        val profile = profileDao.getById(profileId) ?: throw UserFacingException("Profile not found")
        val path = MdbListUrl.listPath(rawUrl) ?: throw UserFacingException("Enter an mdblist.com list URL")
        val entries = fetchOnce(MdbListUrl.CANONICAL_BASE + path)

        val matching = entries.filter { it.mediaType == target.mediaType }
        val tmdbIds = matching.mapNotNull { it.tmdbId }.distinct().take(MAX_IMPORT_ITEMS)
        val restriction = profile.toAgeRestriction()
        val now = clock.millis()

        val lookups: List<Lookup> = coroutineScope {
            tmdbIds.map { id -> async { lookup(id, target.mediaType) } }.awaitAll()
        }
        val resolved = lookups.filterIsInstance<Lookup.Found>()
        if (resolved.isEmpty() && lookups.isNotEmpty()) throw UserFacingException("Couldn't load titles from TMDB")

        val allowed = resolved.filter { ageEngine.isAllowed(it.certification, restriction, it.isAdult) }
        val rows = allowed.map { found ->
            LibraryItemEntity(
                profileId = profile.id,
                tmdbId = found.tmdbId,
                mediaType = target.mediaType,
                title = found.title,
                posterPath = found.posterPath,
                certification = found.certification,
                genreIds = found.genreIds.joinToString(","),
                addedAtMillis = now,
            )
        }
        val inserted = if (rows.isEmpty()) 0 else libraryDao.insertAll(rows).count { it != -1L }
        return LibraryImportResult(
            added = inserted,
            alreadyInLibrary = rows.size - inserted,
            skippedOtherType = entries.size - matching.size,
            blockedByAgeRestriction = resolved.size - allowed.size,
            failed = lookups.size - resolved.size,
        )
    }

    /** Fetches the list, translating transport/parse failures into user-facing messages. */
    private suspend fun fetchOnce(listUrl: String): List<MdbListItem> = try {
        mdbListApi.fetchList(listUrl)
    } catch (e: ResponseException) {
        val status = e.response.status.value
        throw UserFacingException(if (status == HTTP_NOT_FOUND) "MDBList couldn't find that list" else "MDBList answered HTTP $status")
    } catch (e: SerializationException) {
        throw UserFacingException("MDBList returned something that isn't a list")
    } catch (e: IOException) {
        throw UserFacingException("Couldn't reach mdblist.com")
    }

    /** Runs through [MediaHydrator.withTmdb], sharing the app-wide TMDB concurrency gate. */
    private suspend fun lookup(tmdbId: Int, mediaType: MediaType): Lookup = try {
        hydrator.withTmdb { tmdbApi ->
            when (mediaType) {
                MediaType.MOVIE -> tmdbApi.getMovieDetails(tmdbId).let {
                    Lookup.Found(it.id, it.title, it.posterPath, it.usCertification(), it.genres.map { g -> g.id }, it.adult)
                }
                MediaType.TV -> tmdbApi.getTvDetails(tmdbId).let {
                    Lookup.Found(it.id, it.name, it.posterPath, it.usCertification(), it.genres.map { g -> g.id }, it.adult)
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: TmdbApiKeyMissingException) {
        throw UserFacingException("Save a TMDB API key first")
    } catch (e: Exception) {
        // One bad entry (deleted on TMDB, transient error) must not abort the whole import.
        Lookup.Failed
    }

    private sealed interface Lookup {
        data class Found(
            val tmdbId: Int,
            val title: String,
            val posterPath: String?,
            val certification: String?,
            val genreIds: List<Int>,
            val isAdult: Boolean,
        ) : Lookup

        data object Failed : Lookup
    }

    companion object {
        const val MAX_IMPORT_ITEMS = 250
        private const val HTTP_NOT_FOUND = 404
    }
}
