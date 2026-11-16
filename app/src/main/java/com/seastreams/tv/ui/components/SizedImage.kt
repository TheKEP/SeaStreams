package com.seastreams.tv.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Scale
import kotlin.math.roundToInt

/**
 * Network image decoded at a fixed pixel size. Cards animate their width, so letting Coil size from
 * layout constraints would re-request on every frame; a fixed request size decodes exactly once.
 */
@Composable
fun SizedImage(
    url: String?,
    size: DpSize,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    alpha: Float = 1f,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val request = remember(url, size, density, contentScale) {
        sizedImageRequest(context, url, size, density, contentScale.toCoilScale(), crossfade = true)
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = contentScale,
        alignment = alignment,
        alpha = alpha,
        modifier = modifier,
    )
}

/**
 * The one place image requests for fixed-size slots are built. [SizedImage] and the Home image
 * prewarmer both use it, so a prewarmed bitmap has exactly the size and scale the on-screen request
 * asks for and is served from Coil's memory cache without a re-decode.
 */
fun sizedImageRequest(
    context: Context,
    url: String?,
    size: DpSize,
    density: Float,
    scale: Scale,
    crossfade: Boolean,
): ImageRequest = ImageRequest.Builder(context)
    .data(url)
    .size((size.width.value * density).roundToInt(), (size.height.value * density).roundToInt())
    .scale(scale)
    .crossfade(crossfade)
    .build()

/** Same mapping Coil's `AsyncImage` applies when a request does not set a scale. */
fun ContentScale.toCoilScale(): Scale =
    if (this == ContentScale.Fit || this == ContentScale.Inside) Scale.FIT else Scale.FILL
