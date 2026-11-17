package com.seastreams.tv.ui.screens.details

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.core.util.MILLIS_PER_MINUTE
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.TitleDetails
import com.seastreams.tv.ui.components.LocalTransientMessages
import com.seastreams.tv.ui.components.MediaMetadataLine
import com.seastreams.tv.ui.components.MetadataFormat
import com.seastreams.tv.ui.components.SizedImage
import com.seastreams.tv.ui.components.TitleArt
import com.seastreams.tv.ui.screens.playback.PlaybackViewModel
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import kotlinx.coroutines.launch

/**
 * Item Details (`movie-page.jpg`, `show-page.jpg`): full-bleed backdrop with a left vignette, title
 * logo, `👍 81%  2026  Comedy  Adventure  [PG]  1h 49m`, a 3-line synopsis, cast, and the action
 * stack. Focus starts on `Play`; `Back` is handled by the shell, which returns focus to the card
 * that opened this screen.
 *
 * `Play` auto-plays the top stream when *Auto-play top stream* is on; a long-press always opens the
 * stream list. [autoPlay] (Continue Watching with auto-play on) starts playback as soon as it can.
 */
@Composable
fun DetailsScreen(
    profileId: Long,
    item: MediaItem,
    autoPlay: Boolean,
    viewModel: DetailsViewModel,
    playback: PlaybackViewModel,
    onOpenEpisodes: (details: TitleDetails, imdbId: String?) -> Unit,
    onAutoPlayHandled: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(profileId, item.ref) { viewModel.open(profileId, item) }
    val latest by viewModel.state.collectAsState()
    // Ignore a previous title's state for the frame before open() resets it.
    val state = if (latest.media?.ref == item.ref && latest.profileId == profileId) latest else DetailsUiState(profileId, item)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val messages = LocalTransientMessages.current
    val firstButton = remember { FocusRequester() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is DetailsEvent.Message -> messages?.show(event.text)
                is DetailsEvent.OpenTrailer -> try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(YOUTUBE_WATCH + event.youtubeKey)))
                } catch (e: ActivityNotFoundException) {
                    messages?.show("No app on this TV can play trailers")
                }
            }
        }
    }
    // Focus the first button on entry, and again when Retry/Go back replace the stack.
    LaunchedEffect(state.content::class) { firstButton.requestFocusAfterFrame() }
    LaunchedEffect(autoPlay) {
        if (!autoPlay) return@LaunchedEffect
        viewModel.awaitPlayTarget()?.let { playback.play(it, forcePicker = false) }
        onAutoPlayHandled()
    }

    val play: (Boolean) -> Unit = { forcePicker ->
        scope.launch { viewModel.awaitPlayTarget()?.let { playback.play(it, forcePicker) } }
    }
    val actions = detailsActions(
        state = state,
        onPlay = play,
        onTrailer = viewModel::requestTrailer,
        onMoreEpisodes = { state.details?.let { onOpenEpisodes(it, state.imdbId) } },
        onShuffle = {
            val episode = viewModel.shuffleEpisode()
            if (episode == null) {
                messages?.show("No episodes to shuffle yet")
            } else {
                scope.launch { viewModel.awaitPlayTarget(episode)?.let { playback.play(it, forcePicker = false) } }
            }
        },
        onToggleLibrary = viewModel::toggleLibrary,
        onRetry = viewModel::retry,
        onBack = onBack,
    )

    val background = MaterialTheme.colorScheme.background
    val media = state.media ?: item
    Box(Modifier.fillMaxSize().background(background)) {
        SizedImage(url = media.heroBackdropUrl ?: media.backdropUrl, size = BackdropSize, contentDescription = null, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to background.copy(alpha = 0.92f), 0.42f to background.copy(alpha = 0.55f), 0.75f to Color.Transparent),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to background.copy(alpha = 0.7f))))
        Column(
            // Scrolls only when a short screen can't fit the stack, so a focused button is never off screen.
            Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 64.dp, top = 32.dp, bottom = 24.dp).width(560.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TitleArt(media, logoSize = LogoSize, textStyle = MaterialTheme.typography.displaySmall)
            MediaMetadataLine(media, showCertification = true, maxGenres = MAX_GENRES)
            DetailsBody(state, media)
            Spacer(Modifier.height(4.dp))
            DetailsActionStack(actions, firstButton)
        }
    }
}

@Composable
private fun DetailsBody(state: DetailsUiState, media: MediaItem) {
    when (state.content) {
        DetailsContent.Restricted -> Text(
            "This title isn't available on this profile.",
            style = MaterialTheme.typography.titleMedium,
        )
        DetailsContent.Failed -> Text(
            "Couldn't load details. Check the connection and try again.",
            style = MaterialTheme.typography.titleMedium,
        )
        else -> {
            if (media.overview.isNotBlank()) {
                Text(media.overview, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            val cast = state.details?.cast.orEmpty()
            if (cast.isNotEmpty()) {
                Text(
                    "Cast: ${cast.joinToString()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val progressNote = resumeNote(state)
            if (progressNote != null) Text(progressNote, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

/** `42m left` under the synopsis when the movie or next-up episode is part-watched. */
private fun resumeNote(state: DetailsUiState): String? {
    val episode = state.nextUpEpisode
    val point = state.progress.firstOrNull { it.episode == episode && it.inProgress } ?: return null
    val minutesLeft = ((point.durationMs - point.positionMs) / MILLIS_PER_MINUTE).toInt()
    return MetadataFormat.runtime(minutesLeft)?.let { "$it left" }
}

private val BackdropSize = DpSize(960.dp, 540.dp)
private val LogoSize = DpSize(340.dp, 80.dp)
private const val MAX_GENRES = 2
private const val YOUTUBE_WATCH = "https://www.youtube.com/watch?v="
