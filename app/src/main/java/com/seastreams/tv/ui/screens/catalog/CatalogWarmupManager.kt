package com.seastreams.tv.ui.screens.catalog

import coil3.size.Scale
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.util.ApplicationScope
import com.seastreams.tv.domain.model.CatalogRow
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.usecase.GetDiscoverCatalogsUseCase
import com.seastreams.tv.ui.components.PosterSize
import com.seastreams.tv.ui.screens.home.HomeImagePrewarmer
import com.seastreams.tv.ui.screens.home.ImageTarget
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Low-priority background warm-up of the Movies and TV Shows tabs, started once Home has finished
 * its first load, so opening either tab draws populated rows and posters on its first frame.
 *
 * After [delayMillis] (leaving Home's own rendering and focus uncontested) it loads every row of both
 * tabs into the in-memory row cache (and so the HTTP disk cache) and decodes the first
 * [WARM_POSTERS_PER_TAB] `Popular` posters of each tab into Coil's memory cache.
 *
 * Only one warm-up runs at a time: scheduling another profile, or a Kids profile (which has no
 * Movies/TV Shows tabs, PRD §1), cancels the running one.
 */
@Singleton
class CatalogWarmupManager internal constructor(
    private val discover: GetDiscoverCatalogsUseCase,
    private val images: HomeImagePrewarmer,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val delayMillis: Long,
) {
    @Inject constructor(
        discover: GetDiscoverCatalogsUseCase,
        images: HomeImagePrewarmer,
        @ApplicationScope scope: CoroutineScope,
    ) : this(discover, images, scope, Dispatchers.IO, WARMUP_DELAY_MILLIS)

    private class Warmup(val profile: ProfileEntity, val job: Job)

    private val lock = Any()
    private var current: Warmup? = null

    /**
     * Warms [profile]'s Movies and TV Shows tabs in the background. A warm-up already running for
     * this exact profile is left alone; any other one is cancelled first. Kids profiles warm nothing.
     */
    fun schedule(profile: ProfileEntity) {
        synchronized(lock) {
            val running = current
            if (running != null && running.profile == profile && running.job.isActive) return
            running?.job?.cancel()
            current = if (profile.isKids) null else Warmup(profile, scope.launch(dispatcher) {
                delay(delayMillis)
                warmNow(profile)
            })
        }
    }

    /** Cancels any running warm-up (e.g. when leaving the profile). */
    fun cancel() {
        synchronized(lock) {
            current?.job?.cancel()
            current = null
        }
    }

    private suspend fun warmNow(profile: ProfileEntity) {
        val popular = coroutineScope {
            WARMED_TABS.map { type -> async { discover.warm(profile, type) } }.awaitAll()
        }
        images.warm(popularPosterTargets(popular))
    }

    companion object {
        /** Long enough for Home's first frames, hero and row focus to settle before warming starts. */
        const val WARMUP_DELAY_MILLIS = 600L

        /** Roughly the `Popular` posters visible when a tab opens. */
        const val WARM_POSTERS_PER_TAB = 7

        val WARMED_TABS = listOf(MediaType.MOVIE, MediaType.TV)
    }
}

/**
 * The first [perRow] posters of each `Popular` row, built exactly as the tab's cards request them
 * (falling back to the backdrop, at [PosterSize] with [Scale.FILL]) so they are memory-cache hits.
 */
internal fun popularPosterTargets(popularRows: List<CatalogRow?>, perRow: Int = CatalogWarmupManager.WARM_POSTERS_PER_TAB): List<ImageTarget> =
    popularRows.filterNotNull().flatMap { row ->
        row.items.asSequence()
            .mapNotNull { it.posterUrl ?: it.backdropUrl }
            .take(perRow)
            .map { ImageTarget(it, PosterSize, Scale.FILL) }
            .toList()
    }
