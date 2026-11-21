package com.seastreams.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.ui.theme.FocusBorderWidth
import com.seastreams.tv.ui.theme.FocusPill
import com.seastreams.tv.ui.theme.SurfaceCard

/** 2:3 ratio of every grid/search poster. */
const val POSTER_ASPECT_RATIO = 2f / 3f

/**
 * Decode size for grid and search posters. Slots are laid out by their grid column, so the bitmap is
 * decoded once at this fixed size (TMDB `w342` source) rather than per layout pass.
 */
val GridPosterImageSize = DpSize(170.dp, 255.dp)

private val PosterShape = RoundedCornerShape(10.dp)

/**
 * Standard 2:3 poster card for View More, Search and Library (PRD §4, §7). Unlike
 * [StripPosterCard] (which hides under its row's showcase slot) it draws its own [FocusBorderWidth] focus ring.
 * Titles without a poster show their name instead.
 */
@Composable
fun PosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    // Marked on click so `Back` from Details lands on this exact poster.
    val origin = rememberFocusOrigin()
    Surface(
        onClick = {
            origin.mark()
            onClick()
        },
        modifier = modifier
            .focusRequester(origin.requester)
            .aspectRatio(POSTER_ASPECT_RATIO)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(PosterShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(FocusBorderWidth, FocusPill), shape = PosterShape)),
        colors = ClickableSurfaceDefaults.colors(containerColor = SurfaceCard, focusedContainerColor = SurfaceCard),
    ) {
        Box(Modifier.fillMaxSize()) {
            val poster = item.posterUrl
            if (poster != null) {
                SizedImage(poster, GridPosterImageSize, contentDescription = item.title, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                )
            }
            WatchedBadgeIfWatched(item)
        }
    }
}

/** Non-focusable grey 2:3 tile shown while a grid loads. */
@Composable
fun PosterPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(POSTER_ASPECT_RATIO).clip(PosterShape).background(SurfaceCard))
}
