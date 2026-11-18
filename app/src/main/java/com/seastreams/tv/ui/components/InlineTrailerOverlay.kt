package com.seastreams.tv.ui.components

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Icon
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.player.InlineTrailerSlot
import com.seastreams.tv.player.InlineTrailerState
import com.seastreams.tv.player.playsIn
import com.seastreams.tv.ui.screens.catalog.InlineTrailerViewModel

/** The profile shell's trailer preview host; `null` where previews aren't offered. */
val LocalInlineTrailers = staticCompositionLocalOf<InlineTrailerViewModel?> { null }

/**
 * Draws [item]'s trailer inside its expanded 16:9 card while it plays (PRD §4): the video fills the
 * artwork frame (the card's focus ring and the metadata below are untouched), with a small white
 * muted-speaker badge in the top-right corner when muted, moved left of the watched checkmark when
 * there is one.
 *
 * Single-Card Binding: the surface is attached only when this card's [slot] owns the preview *and*
 * the card holds D-pad focus ([playsIn]). The same title left wide on another, unfocused row draws
 * nothing here, so it keeps its static backdrop and never takes the shared player's surface.
 */
@Composable
fun BoxScope.InlineTrailerOverlay(item: MediaItem, slot: InlineTrailerSlot, cardFocused: Boolean) {
    val trailers = LocalInlineTrailers.current ?: return
    val state by trailers.state.collectAsState()
    if (!state.playsIn(slot, cardFocused)) return
    val playing = state as? InlineTrailerState.Playing ?: return
    val player by trailers.player.collectAsState()
    val current = player ?: return
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        TrailerTexture(current)
    }
    if (playing.muted) {
        val watched = item.ref in LocalWatchedTitles.current
        val end = if (watched) BadgeInset + BadgeSize + MuteGap else BadgeInset
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = BadgeInset, end = end).size(BadgeSize)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(SeaIcons.VolumeOff, contentDescription = "Muted", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

/** A TextureView (clipped by the card's rounded shape, unlike a SurfaceView) bound to [player]. */
@Composable
private fun TrailerTexture(player: ExoPlayer) {
    var view by remember { mutableStateOf<TextureView?>(null) }
    DisposableEffect(player, view) {
        val texture = view
        if (texture != null) player.setVideoTextureView(texture)
        onDispose { if (texture != null) player.clearVideoTextureView(texture) }
    }
    AndroidView(factory = { context -> TextureView(context).also { view = it } }, modifier = Modifier.fillMaxSize())
}

private val MuteGap = 6.dp
