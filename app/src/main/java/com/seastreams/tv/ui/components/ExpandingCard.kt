package com.seastreams.tv.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.ui.theme.Obsidian
import com.seastreams.tv.ui.theme.SurfaceCard
import com.seastreams.tv.ui.theme.TextPrimary
import com.seastreams.tv.ui.theme.focusRing

/** Row artwork height: 2:3 posters are 145 × 218dp, the expanded 16:9 card 388 × 218dp. */
val CardHeight = 218.dp
val PosterWidth = 145.dp
val ExpandedCardWidth = 388.dp
val PosterSize = DpSize(PosterWidth, CardHeight)
private val ExpandedSize = DpSize(ExpandedCardWidth, CardHeight)
private val CardLogoSize = DpSize(216.dp, 68.dp)
private val CardShape = RoundedCornerShape(12.dp)

/** Short enough to read as instant, long enough to be smooth (≈11 frames at 60fps). */
private const val EXPAND_MILLIS = 180

/**
 * Catalog card that is a 2:3 poster at rest and a 16:9 backdrop (with title logo) while [expanded].
 * Expansion is decided by the row (its remembered card stays wide after focus leaves the row);
 * the focus ring only follows real D-pad focus. Both images are decoded once at their final size,
 * so the animation only re-lays-out and re-draws.
 */
@Composable
fun ExpandingMediaCard(
    item: MediaItem,
    expanded: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.(expanded: Boolean) -> Unit = {},
) {
    ExpandingCardSurface(
        onClick = onClick,
        onFocusChanged = { if (it) onFocused() },
        modifier = modifier,
        wide = { expanded },
    ) { expanded ->
        val backdropAlpha by animateFloatAsState(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = tween(EXPAND_MILLIS),
            label = "cardBackdrop",
        )
        val posterUrl = item.posterUrl ?: item.backdropUrl
        if (backdropAlpha < 1f) {
            if (posterUrl != null) {
                SizedImage(posterUrl, PosterSize, contentDescription = item.title, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                )
            }
        }
        if (backdropAlpha > 0f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = backdropAlpha }.background(SurfaceCard)) {
                SizedImage(item.backdropUrl ?: posterUrl, ExpandedSize, contentDescription = null, modifier = Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.45f to Color.Transparent, 1f to Obsidian.copy(alpha = 0.85f)),
                    ),
                )
                TitleArt(
                    item,
                    logoSize = CardLogoSize,
                    textStyle = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                )
            }
        }
        overlay(expanded)
    }
}

/**
 * The `>>  View More` card at position 21 (index 20) of a capped row. It widens only while it
 * holds focus, so it never stays behind as an empty 16:9 card once focus leaves the row.
 */
@Composable
fun ViewMoreCard(onClick: () -> Unit, onFocusChanged: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    ExpandingCardSurface(onClick = onClick, onFocusChanged = onFocusChanged, modifier = modifier, wide = { it }) { _ ->
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy((-28).dp)) {
                repeat(2) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(56.dp),
                    )
                }
            }
            Text("View More", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Shared focusable shell: animates between 2:3 and 16:9 as [wide] (given the card's focus) changes,
 * and draws the focus ring only while this card holds D-pad focus ([cardAppearance]).
 */
@Composable
private fun ExpandingCardSurface(
    onClick: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier,
    wide: (focused: Boolean) -> Boolean,
    content: @Composable BoxScope.(expanded: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val appearance = cardAppearance(wide = wide(focused), focused = focused)
    val width by animateDpAsState(
        targetValue = if (appearance.wide) ExpandedCardWidth else PosterWidth,
        animationSpec = tween(EXPAND_MILLIS, easing = FastOutSlowInEasing),
        label = "cardWidth",
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .width(width)
            .height(CardHeight)
            .onFocusChanged {
                if (focused != it.isFocused) {
                    focused = it.isFocused
                    onFocusChanged(it.isFocused)
                }
            }
            .focusRing(appearance.focusRing, CardShape),
        shape = ClickableSurfaceDefaults.shape(CardShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = SurfaceCard, focusedContainerColor = SurfaceCard),
    ) {
        Box(Modifier.fillMaxSize()) { content(appearance.wide) }
    }
}
