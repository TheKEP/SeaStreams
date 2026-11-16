package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.dao.CatalogDao
import com.seastreams.tv.core.database.entity.CatalogPlacement
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.network.MdbListApiService
import com.seastreams.tv.core.network.MdbListItem
import com.seastreams.tv.core.util.ApplicationScope
import com.seastreams.tv.core.util.SharedLoadCache
import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.model.AgeRestriction
import com.seastreams.tv.domain.model.CatalogRow
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.CatalogRowState
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.RowContent
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Builds the Home hero and catalog rows for a profile (PRD §3).
 *
 * Every title passes through [AgeRestrictionEngine] here, before it can reach a ViewModel, so Kids
 * profiles only ever receive titles inside their rating ceilings (CLAUDE.md rule 6).
 *
 * Hero and row loads are shared through in-memory caches, so a [prefetch] started while a profile
 * card is focused is simply awaited when Home opens instead of being fetched again.
 */
@Singleton
class GetHomeCatalogsUseCase @Inject constructor(
    /** Lazy so the HTTP stack is built on IO, never during ViewModel creation on the main thread. */
    private val mdbList: Lazy<MdbListApiService>,
    private val hydrator: MediaHydrator,
    private val catalogDao: CatalogDao,
    private val ageEngine: AgeRestrictionEngine,
    @ApplicationScope scope: CoroutineScope,
    clock: Clock,
) {
    private val heroCache = SharedLoadCache<HeroKey, List<MediaItem>>(scope, PREFETCH_TTL_MILLIS, clock::millis)
    private val rowCache = SharedLoadCache<RowKey, CatalogRow>(scope, PREFETCH_TTL_MILLIS, clock::millis)

    /**
     * Starts loading [profile]'s hero and its first [PREFETCH_ROW_COUNT] Home rows in the background
     * and waits for them. The loads keep running if the caller stops waiting; Home picks them up.
     * Failed parts come back as `null` / are left out rather than throwing.
     */
    suspend fun prefetch(profile: ProfileEntity): HomePrefetch {
        val restriction = profile.toAgeRestriction()
        val hero = heroCache.start(profile.heroKey()) { loadHero(profile) }
        val specs = HomeCatalogSources.rowSpecs(profile.isKids, catalogDao.observeByPlacement(CatalogPlacement.HOME).first())
        val rows = specs.take(PREFETCH_ROW_COUNT).map { spec -> rowCache.start(RowKey(spec.listUrl, restriction)) { loadRow(spec, restriction) } }
        return HomePrefetch(
            hero = orNullUnlessCancelled { hero.await() },
            rows = rows.mapNotNull { row -> orNullUnlessCancelled { row.await() } },
        )
    }

    /**
     * The hero for [profile]: the prefetched one when there is a fresh one, else a new load. Each
     * call consumes the cached hero, so the next visit to Home gets a newly shuffled carousel.
     * @throws IOException when neither source list could be loaded.
     */
    suspend fun takeHero(profile: ProfileEntity): List<MediaItem> = heroCache.take(profile.heroKey()) { loadHero(profile) }

    /**
     * Fixed rows for the profile type plus the user's Home MDBLists. Emits immediately with every row
     * loading, then again as each row finishes (rows load in parallel).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeRows(profile: ProfileEntity): Flow<List<CatalogRowState>> =
        catalogDao.observeByPlacement(CatalogPlacement.HOME)
            .map { custom -> HomeCatalogSources.rowSpecs(profile.isKids, custom) }
            .distinctUntilChanged()
            .flatMapLatest { specs -> loadRows(specs, profile.toAgeRestriction()) }

    /** Loads one row: the first [ROW_CAP] age-approved titles of its list. */
    suspend fun loadRow(spec: CatalogRowSpec, restriction: AgeRestriction?): CatalogRow =
        collectAllowed(fetchRefs(spec.listUrl), restriction, ROW_CAP)

    /**
     * Hero titles in random order: the top [HERO_SOURCE_TOP_N] of the profile's movie list plus the
     * top [HERO_SOURCE_TOP_N] of its show list (JustWatch charts for Adults, trending kids lists for
     * Kids), shuffled and capped at [HERO_SIZE]. Only age-approved titles with backdrop art are used;
     * the pool is never back-filled from deeper in the lists, so a strict Kids ceiling can yield fewer.
     * @throws IOException when neither source list could be loaded.
     */
    suspend fun loadHero(profile: ProfileEntity, random: Random = Random.Default): List<MediaItem> {
        val (movieUrl, showUrl) = if (profile.isKids) {
            HomeCatalogSources.KIDS_MOVIES to HomeCatalogSources.KIDS_SHOWS
        } else {
            HomeCatalogSources.HERO_ADULT_MOVIES to HomeCatalogSources.HERO_ADULT_SHOWS
        }
        val (movies, shows) = coroutineScope {
            val m = async { fetchRefsOrNull(movieUrl) }
            val s = async { fetchRefsOrNull(showUrl) }
            m.await() to s.await()
        }
        if (movies == null && shows == null) throw IOException("Couldn't load hero lists")
        val pool = buildHeroPool(movies.orEmpty(), shows.orEmpty(), random)
        return collectAllowed(pool, profile.toAgeRestriction(), HERO_SIZE) { it.heroBackdropUrl != null }.items
    }

    private fun loadRows(specs: List<CatalogRowSpec>, restriction: AgeRestriction?): Flow<List<CatalogRowState>> =
        channelFlow {
            val states = specs.map { CatalogRowState(it, RowContent.Loading) }.toMutableList()
            val lock = Mutex()
            send(states.toList())
            specs.forEachIndexed { index, spec ->
                launch {
                    val content = orNullUnlessCancelled {
                        RowContent.Loaded(rowCache.get(RowKey(spec.listUrl, restriction)) { loadRow(spec, restriction) })
                    } ?: RowContent.Failed
                    lock.withLock {
                        states[index] = states[index].copy(content = content)
                        send(states.toList())
                    }
                }
            }
        }

    /**
     * Hydrates [refs] in order, keeping titles allowed by [restriction] (and [accept]) until [cap]
     * are collected. Titles stripped by the filter are back-filled from further down the list, up to
     * [MAX_SCAN_FACTOR] × [cap] lookups.
     */
    internal suspend fun collectAllowed(
        refs: List<MediaRef>,
        restriction: AgeRestriction?,
        cap: Int,
        accept: (MediaItem) -> Boolean = { true },
    ): CatalogRow {
        val accepted = ArrayList<MediaItem>(cap)
        val scanLimit = minOf(refs.size, cap * MAX_SCAN_FACTOR)
        var scanned = 0
        while (accepted.size < cap && scanned < scanLimit) {
            val batch = refs.subList(scanned, minOf(scanned + cap - accepted.size, scanLimit))
            scanned += batch.size
            accepted += hydrator.hydrateAll(batch).filter { item ->
                accept(item) && ageEngine.isAllowed(item.certification, restriction, item.isAdultContent)
            }
        }
        return CatalogRow(items = accepted.take(cap), hasMore = accepted.size > cap || scanned < refs.size)
    }

    private suspend fun fetchRefs(listUrl: String): List<MediaRef> =
        withContext(Dispatchers.IO) { mdbList.get().fetchList(listUrl) }.toRefs()

    private suspend fun fetchRefsOrNull(listUrl: String): List<MediaRef>? = orNullUnlessCancelled { fetchRefs(listUrl) }

    private fun ProfileEntity.heroKey() = HeroKey(id, isKids, toAgeRestriction())

    private data class HeroKey(val profileId: Long, val isKids: Boolean, val restriction: AgeRestriction?)

    private data class RowKey(val listUrl: String, val restriction: AgeRestriction?)

    companion object {
        /** Rows [prefetch] loads ahead of Home: the ones visible without scrolling. */
        const val PREFETCH_ROW_COUNT = 2

        /** How long a prefetched hero/row is reused; the HTTP cache (6–12h) backs it afterwards. */
        const val PREFETCH_TTL_MILLIS = 10 * 60 * 1000L

        /** Titles per row before the `View More` card (PRD §4). */
        const val ROW_CAP = 30
        /** Hero items per Home load (PRD §3). */
        const val HERO_SIZE = 10

        /** Entries taken from the top of each hero source list (PRD §3). */
        const val HERO_SOURCE_TOP_N = 15
        const val MAX_SCAN_FACTOR = 4

        /** The top [HERO_SOURCE_TOP_N] of each list, de-duplicated and shuffled. */
        fun buildHeroPool(movies: List<MediaRef>, shows: List<MediaRef>, random: Random): List<MediaRef> =
            (movies.take(HERO_SOURCE_TOP_N) + shows.take(HERO_SOURCE_TOP_N)).distinct().shuffled(random)
    }
}

/** What [GetHomeCatalogsUseCase.prefetch] resolved: hero titles (`null` if it failed) and the top rows. */
data class HomePrefetch(val hero: List<MediaItem>?, val rows: List<CatalogRow>)

/** Usable references in list order: entries without a TMDB id or a known media type are dropped. */
internal fun List<MdbListItem>.toRefs(): List<MediaRef> = mapNotNull { item ->
    val id = item.tmdbId ?: return@mapNotNull null
    val type = item.mediaType ?: return@mapNotNull null
    MediaRef(id, type)
}.distinct()
