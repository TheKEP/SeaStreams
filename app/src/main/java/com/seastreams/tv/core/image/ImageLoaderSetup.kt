package com.seastreams.tv.core.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath

/** Coil's disk cache folder inside `context.cacheDir` (Coil's own default name, so cached art carries over). */
const val IMAGE_CACHE_DIR = "image_cache"

/** Hard LRU cap for decoded-source image files on disk; never a percentage of free storage. */
const val IMAGE_DISK_CACHE_BYTES: Long = 150L * 1024 * 1024

/** Share of the app's memory class Coil may hold as decoded bitmaps (Home prewarm relies on it). */
const val IMAGE_MEMORY_CACHE_PERCENT = 0.25

/**
 * The app-wide Coil loader (`SeaStreamsApp` is its `SingletonImageLoader.Factory`). Both caches are
 * built lazily on first use, on Coil's own dispatchers, so creating the loader never touches disk on
 * the main thread. Coil keeps its own uncached OkHttp fetcher, so images aren't stored twice.
 */
fun buildImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
    .memoryCache { MemoryCache.Builder().maxSizePercent(context, IMAGE_MEMORY_CACHE_PERCENT).build() }
    .diskCache {
        DiskCache.Builder()
            .directory(context.cacheDir.resolve(IMAGE_CACHE_DIR).toOkioPath())
            .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
            .build()
    }
    .build()
