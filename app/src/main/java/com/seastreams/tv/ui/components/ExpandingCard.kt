package com.seastreams.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import com.seastreams.tv.ui.theme.SurfaceCard
import com.seastreams.tv.ui.theme.TextPrimary
import com.seastreams.tv.ui.theme.focusRing
import com.seastreams.tv.ui.theme.rememberSelectLongPress
import com.seastreams.tv.ui.theme.selectLongPress

/** Row artwork height: strip posters are 145 × 218dp, the row's 16:9 showcase slot 388 × 218dp. */
val CardHeight = 218.dp
val PosterWidth = 145.dp
val ExpandedCardWidth = 388.dp
val PosterSize = DpSize(PosterWidth, CardHeight)
internal val ExpandedSize = DpSize(ExpandedCardWidth, CardHeight)
internal val CardShape = RoundedCornerShape(12.dp)

/**
 * Fixed-width 2:3 poster in a catalog row's strip (PRD §4). It holds real D-pad focus but never draws
 * a focus ring and never changes width: while focused it sits underneath the row's
 * [CatalogShowcaseSlot], which shows the item large and draws the ring instead.
 */
@Composable
fun StripPosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    /** Focus left this poster (inline trailer previews stop). */
    onBlurred: () -> Unit = {},
    /** Held `OK`; `null` (the default) leaves long-press unhandled. */
    onLongClick: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    StripCardSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        onFocusChanged = { if (it) onFocused() else onBlurred() },
        modifier = modifier,
        ringWhenFocused = false,
    ) {
        val posterUrl = item.posterUrl ?: item.backdropUrl
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
        overlay()
        WatchedBadgeIfWatched(item)
    }
}

/**
 * The `>>  View More` card at position 21 (index 20) of a capped row: a fixed 2:3 card that draws its
 * own focus ring while focused (the showcase slot drops its ring meanwhile).
 */
@Composable
fun ViewMoreCard(
    onClick: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    StripCardSurface(
        onClick = onClick,
        onFocusChanged = onFocusChanged,
        modifier = modifier,
        ringWhenFocused = true,
    ) {
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

/** Shared fixed-size focusable shell of strip cards; the ring is drawn only when [ringWhenFocused]. */
@Composable
private fun StripCardSurface(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier,
    ringWhenFocused: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val origin = rememberFocusOrigin()
    // Long-press state must reset when the menu it opens takes focus (not the Surface's onLongClick).
    val longPress = rememberSelectLongPress()
    Surface(
        onClick = {
            if (!longPress.consumeClick()) {
                origin.mark()
                onClick()
            }
        },
        modifier = modifier
            .focusRequester(origin.requester)
            .selectLongPress(longPress, onLongClick)
            .size(PosterSize)
            .onFocusChanged {
                if (focused != it.isFocused) {
                    focused = it.isFocused
                    onFocusChanged(it.isFocused)
                }
            }
            .focusRing(ringWhenFocused && focused, CardShape),
        shape = ClickableSurfaceDefaults.shape(CardShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = SurfaceCard, focusedContainerColor = SurfaceCard),
    ) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}
