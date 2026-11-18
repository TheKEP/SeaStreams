package com.seastreams.tv.ui.screens.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.Episode
import com.seastreams.tv.domain.playback.ProgressPoint
import com.seastreams.tv.ui.components.ArtworkProgressIndicator
import com.seastreams.tv.ui.components.MetadataFormat
import com.seastreams.tv.ui.components.SizedImage
import com.seastreams.tv.ui.components.artworkProgress
import com.seastreams.tv.ui.components.rememberFocusOrigin
import com.seastreams.tv.ui.theme.SurfaceCard
import com.seastreams.tv.ui.theme.TextPrimary
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.focusRing
import com.seastreams.tv.ui.theme.rememberSelectLongPress
import com.seastreams.tv.ui.theme.selectLongPress
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One episode (`episodes-page.jpg`): numbered 16:9 still (blurred when [blurStill]), title,
 * `30 Mar, 2025  (1h)` and synopsis. Focus draws the ring on the still. Click plays the episode;
 * a long-press opens the episode menu ([onLongClick] gets this row's focus requester to return to).
 */
@Composable
fun EpisodeRow(
    episode: Episode,
    progress: ProgressPoint?,
    blurStill: Boolean,
    onPlay: () -> Unit,
    onLongClick: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val origin = rememberFocusOrigin()
    // Long-press state must reset when the episode menu takes focus (not the Surface's onLongClick).
    val longPress = rememberSelectLongPress()
    Surface(
        onClick = {
            if (!longPress.consumeClick()) {
                origin.mark()
                onPlay()
            }
        },
        modifier = modifier
            .focusRequester(origin.requester)
            .selectLongPress(longPress) { onLongClick(origin.requester) }
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(StillShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = TextPrimary,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = TextPrimary,
        ),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(StillSize).focusRing(focused, StillShape).padding(3.dp).clip(StillShape).background(SurfaceCard)) {
                SizedImage(
                    url = episode.stillUrl,
                    size = StillSize,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (blurStill) Modifier.blur(BlurRadius, BlurredEdgeTreatment.Rectangle) else Modifier),
                )
                Text(
                    episode.ref.episode.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
                // Watched: top-right checkmark; part-watched: bottom bar (PRD §1, §6).
                if (progress != null) ArtworkProgressIndicator(artworkProgress(progress.positionMs, progress.durationMs))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(episode.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                episodeSubtitle(episode)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TextSecondary) }
                if (episode.overview.isNotBlank()) {
                    Text(episode.overview, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** `30 Mar, 2025  (1h)`; missing parts are skipped. */
internal fun episodeSubtitle(episode: Episode): String? {
    val date = episode.airDate?.format(AirDateFormat)
    val runtime = MetadataFormat.runtime(episode.runtimeMinutes)?.let { "($it)" }
    return listOfNotNull(date, runtime).joinToString("  ").ifEmpty { null }
}

private val AirDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, yyyy", Locale.US)
private val StillSize = DpSize(256.dp, 144.dp)
private val StillShape = RoundedCornerShape(10.dp)
private val BlurRadius = 18.dp
