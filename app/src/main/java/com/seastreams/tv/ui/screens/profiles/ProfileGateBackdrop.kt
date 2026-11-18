package com.seastreams.tv.ui.screens.profiles

import android.util.DisplayMetrics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import coil3.size.Scale
import com.seastreams.tv.ui.components.SizedImage
import com.seastreams.tv.ui.screens.home.ImageTarget

/** The backdrop fills the right 76% of the Profile Gate at full screen height. */
private const val BACKDROP_WIDTH_FRACTION = 0.76f

/**
 * The backdrop exactly as [ProfileGateBackdrop] requests it. The splash-time prewarm in
 * [ProfileSelectViewModel] and the on-screen image both use this, so the prewarmed bitmap is a
 * Coil memory-cache hit on the gate's first frame (same URL, size and scale).
 */
fun profileGateBackdropTarget(metrics: DisplayMetrics, url: String): ImageTarget {
    val widthDp = metrics.widthPixels / metrics.density
    val heightDp = metrics.heightPixels / metrics.density
    return ImageTarget(url, DpSize((widthDp * BACKDROP_WIDTH_FRACTION).dp, heightDp.dp), Scale.FILL)
}

/** Full-bleed child-safe artwork on the right of `ProfileSelectScreen`, blended into the profile column. */
@Composable
fun ProfileGateBackdrop(url: String?, modifier: Modifier = Modifier) {
    val metrics = LocalContext.current.applicationContext.resources.displayMetrics
    Box(modifier.fillMaxHeight().fillMaxWidth(BACKDROP_WIDTH_FRACTION)) {
        if (url != null) {
            val target = remember(url, metrics) { profileGateBackdropTarget(metrics, url) }
            SizedImage(url = target.url, size = target.size, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        // Left vignette blending the artwork into the profile column.
        val background = MaterialTheme.colorScheme.background
        Box(
            Modifier.fillMaxHeight().width(360.dp)
                .background(Brush.horizontalGradient(listOf(background, background.copy(alpha = 0.6f), Color.Transparent))),
        )
    }
}
