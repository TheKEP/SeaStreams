package com.seastreams.tv.ui.screens.home

import android.content.Context
import androidx.compose.ui.unit.DpSize
import coil3.SingletonImageLoader
import coil3.size.Scale
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.ui.components.HeroImageSize
import com.seastreams.tv.ui.components.HeroLogoSize
import com.seastreams.tv.ui.components.PosterSize
import com.seastreams.tv.ui.components.sizedImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** One image as a Home slot will request it. */
data class ImageTarget(val url: String, val size: DpSize, val scale: Scale)

/**
 * Decodes Home's first-screen images into Coil's memory cache ahead of time, so the hero and the
 * first posters are drawn on Home's first frame instead of popping in.
 */
@Singleton
class HomeImagePrewarmer @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Enqueues [targets] on the app's image loader and waits until each has finished (or failed).
     * The requests belong to the loader, so they keep decoding if the caller stops waiting.
     */
    suspend fun warm(targets: List<ImageTarget>) {
        if (targets.isEmpty()) return
        val loader = SingletonImageLoader.get(context)
        val density = context.resources.displayMetrics.density
        targets
            .map { target ->
                loader.enqueue(sizedImageRequest(context, target.url, target.size, density, target.scale, crossfade = false))
            }
            .forEach { it.job.await() }
    }
}

/**
 * Images visible on Home's first screen: the first hero slide's backdrop and logo, then the first
 * [maxPosters] posters across [rows] in display order (`posterUrl`, falling back to the backdrop, as
 * the cards do).
 */
fun firstScreenImages(hero: List<MediaItem>?, rows: List<List<MediaItem>>, maxPosters: Int = FIRST_SCREEN_POSTERS): List<ImageTarget> =
    buildList {
        hero?.firstOrNull()?.let { item ->
            item.heroBackdropUrl?.let { add(ImageTarget(it, HeroImageSize, Scale.FILL)) }
            item.logoUrl?.let { add(ImageTarget(it, HeroLogoSize, Scale.FIT)) }
        }
        rows.asSequence()
            .flatten()
            .mapNotNull { it.posterUrl ?: it.backdropUrl }
            .take(maxPosters)
            .forEach { add(ImageTarget(it, PosterSize, Scale.FILL)) }
    }

/** Roughly one screen width of posters plus the next few the user reaches by pressing Right. */
const val FIRST_SCREEN_POSTERS = 12
