package com.seastreams.tv.data.catalog

import com.seastreams.tv.core.database.dao.CatalogDao
import com.seastreams.tv.core.database.entity.CatalogPlacement
import com.seastreams.tv.core.database.entity.CustomCatalogEntity
import com.seastreams.tv.core.network.MdbListApiService
import com.seastreams.tv.core.network.MdbListUrl
import com.seastreams.tv.data.UserFacingException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** User-added MDBList rows shown on the Home, Movies or TV Shows tabs. */
@Singleton
class CatalogRepository @Inject constructor(
    private val catalogDao: CatalogDao,
    private val mdbListApi: MdbListApiService,
    private val clock: Clock,
) {
    fun observeCatalogs(): Flow<List<CustomCatalogEntity>> = catalogDao.observeAll()

    /**
     * Adds an MDBList row to [placement]. The list is fetched once up front so typos and private
     * lists are reported immediately instead of rendering as an empty row (the fetch is HTTP-cached).
     */
    suspend fun add(rawUrl: String, placement: CatalogPlacement, title: String?): CustomCatalogEntity {
        val path = MdbListUrl.listPath(rawUrl)
            ?: throw UserFacingException("Enter an mdblist.com list URL, e.g. https://mdblist.com/lists/owner/list-name")
        val canonicalUrl = CANONICAL_BASE + path
        val trimmedTitle = title?.trim().orEmpty()
        if (trimmedTitle.length > MAX_TITLE_LENGTH) throw UserFacingException("Titles can be at most $MAX_TITLE_LENGTH characters")
        fetchOrExplain(mdbListApi, canonicalUrl)
        val entity = CustomCatalogEntity(
            listUrl = canonicalUrl,
            title = trimmedTitle.ifEmpty { titleFromPath(path) },
            placement = placement,
            sortOrder = catalogDao.nextSortOrder(placement),
            addedAtMillis = clock.millis(),
        )
        val id = catalogDao.insert(entity)
        if (id == -1L) throw UserFacingException("That list is already on this tab")
        return entity.copy(id = id)
    }

    suspend fun remove(catalogId: Long) {
        catalogDao.getById(catalogId) ?: throw UserFacingException("Catalog not found")
        catalogDao.delete(catalogId)
    }

    companion object {
        const val CANONICAL_BASE = "https://mdblist.com"
        const val MAX_TITLE_LENGTH = 60

        /** `/lists/official/movies/most-watched-week` → `Most Watched Week`. */
        fun titleFromPath(path: String): String =
            path.trimEnd('/').substringAfterLast('/')
                .split('-', '_')
                .filter { it.isNotBlank() }
                .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
                .ifEmpty { "MDBList" }
    }
}

/** Fetches an MDBList list, translating transport/parse failures into user-facing messages. */
internal suspend fun fetchOrExplain(api: MdbListApiService, listUrl: String) = try {
    api.fetchList(listUrl)
} catch (e: ResponseException) {
    val status = e.response.status.value
    throw UserFacingException(if (status == HTTP_NOT_FOUND) "MDBList couldn't find that list" else "MDBList answered HTTP $status")
} catch (e: SerializationException) {
    throw UserFacingException("MDBList returned something that isn't a list")
} catch (e: IOException) {
    throw UserFacingException("Couldn't reach mdblist.com")
}

private const val HTTP_NOT_FOUND = 404
