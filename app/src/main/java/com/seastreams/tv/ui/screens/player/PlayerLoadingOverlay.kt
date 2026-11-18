package com.seastreams.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.PlaybackArtwork
import com.seastreams.tv.ui.components.SizedImage

/**
 * Movie/show loading branding (PRD §9, `video-loading.jpg`). [LoadingVisual.BACKDROP_AND_LOGO] covers
 * the screen with the title's backdrop (no scrim) and the centred logo; [LoadingVisual.LOGO_ONLY] draws
 * just the logo over the video. The logo (or title text when there is none) pulses until playback
 * begins; both fade out when the first frame arrives.
 */
@Composable
fun PlayerLoadingOverlay(artwork: PlaybackArtwork, visual: LoadingVisual, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val screen = DpSize(maxWidth, maxHeight)
        AnimatedVisibility(
            visible = visual == LoadingVisual.BACKDROP_AND_LOGO,
            enter = EnterTransition.None,
            exit = fadeOut(tween(FADE_OUT_MS)),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                artwork.backdropUrl?.let { url ->
                    SizedImage(url = url, size = screen, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
            }
        }
        AnimatedVisibility(
            visible = visual != LoadingVisual.NONE,
            enter = fadeIn(tween(FADE_IN_MS)),
            exit = fadeOut(tween(FADE_OUT_MS)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            PulsingTitleArt(artwork)
        }
    }
}

/** The title logo (or bold title text) with an endless fade-in/fade-out alpha pulse. */
@Composable
private fun PulsingTitleArt(artwork: PlaybackArtwork) {
    val alpha by rememberInfiniteTransition(label = "loadingPulse").animateFloat(
        initialValue = PULSE_MIN_ALPHA,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_HALF_PERIOD_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "loadingPulseAlpha",
    )
    // Read in the draw phase only, so the pulse never recomposes the player.
    val pulse = Modifier.graphicsLayer { this.alpha = alpha }
    val logo = artwork.logoUrl
    when {
        logo != null -> SizedImage(
            url = logo,
            size = LogoSize,
            contentDescription = artwork.title,
            contentScale = ContentScale.Fit,
            modifier = pulse.size(LogoSize),
        )
        artwork.title.isNotBlank() -> Text(
            artwork.title,
            style = MaterialTheme.typography.displayMedium.copy(shadow = TitleShadow),
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = pulse.padding(horizontal = 96.dp),
        )
    }
}

private val LogoSize = DpSize(520.dp, 180.dp)
private val TitleShadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 4f), blurRadius = 16f)
private const val PULSE_MIN_ALPHA = 0.3f
private const val PULSE_HALF_PERIOD_MS = 1_100
private const val FADE_IN_MS = 250
private const val FADE_OUT_MS = 400
