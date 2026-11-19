package com.seastreams.tv.ui.screens.details

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.TitleDetails
import com.seastreams.tv.domain.playback.ShuffleMode
import com.seastreams.tv.ui.components.LocalFocusOrigins
import com.seastreams.tv.ui.components.LocalLayerCovered
import com.seastreams.tv.ui.components.LocalTransientMessages
import com.seastreams.tv.ui.components.MediaMetadataLine
import com.seastreams.tv.ui.components.SizedImage
import com.seastreams.tv.ui.components.TitleArt
import com.seastreams.tv.ui.screens.playback.PlaybackViewModel
import com.seastreams.tv.ui.screens.playback.RESTORE_FRAMES
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.awaitNextFrame
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.tryRequestFocus
import kotlinx.coroutines.launch

/**
 * Item Details (`movie-page.jpg`, `show-page.jpg`): full-bleed backdrop with a left vignette, title
 * logo, `👍 81%  2026  Comedy  Adventure  [PG]  1h 49m`, a 5-line synopsis and the action stack on the
 * left, and the non-focusable Director + Cast card ([DetailsCastGrid]) vertically centred on the right. Focus starts on `Play`; `Back` is handled by the shell, which returns focus to the card
 * that opened this screen.
 *
 * `Play` auto-plays the top stream when *Auto-play top stream* is on; a long-press always opens the
 * stream list.
 */
@Composable
fun DetailsScreen(
    profileId: Long,
    item: MediaItem,
    viewModel: DetailsViewModel,
    playback: PlaybackViewModel,
    onOpenEpisodes: (details: TitleDetails, imdbId: String?) -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(profileId, item.ref) { viewModel.open(profileId, item) }
    val latest by viewModel.state.collectAsState()
    // Ignore a previous title's state for the frame before open() resets it.
    val state = if (latest.media?.ref == item.ref && latest.profileId == profileId) latest else DetailsUiState(profileId, item)
    val scope = rememberCoroutineScope()
    val messages = LocalTransientMessages.current
    val firstButton = remember { FocusRequester() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is DetailsEvent.Message -> messages?.show(event.text)
                // Full-screen in the built-in player; YouTube only if extraction fails (PRD §6).
                is DetailsEvent.OpenTrailer -> playback.playTrailer(event.title, event.youtubeKey)
            }
        }
    }
    // Focus the first button on entry, and again when Retry/Go back replace the stack.
    LaunchedEffect(state.content::class) { firstButton.requestFocusAfterFrame() }
    // Playback closing without a usable mark lands on Play/Resume.
    val origins = LocalFocusOrigins.current
    DisposableEffect(origins, firstButton) {
        val token = origins?.registerFallback(firstButton)
        onDispose { token?.let { origins.unregisterFallback(it) } }
    }
    // Uncovered (player, stream list or Episodes closed) with nothing focused here: never leave focus
    // on nothing. Runs only after the shell's exact-origin restore has had its frames.
    var pageHasFocus by remember { mutableStateOf(false) }
    val covered = LocalLayerCovered.current
    LaunchedEffect(covered) {
        if (covered) return@LaunchedEffect
        repeat(RESTORE_FRAMES + 2) { awaitNextFrame() }
        if (!pageHasFocus) firstButton.tryRequestFocus()
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
                scope.launch {
                    viewModel.awaitPlayTarget(episode, ShuffleMode.Show)?.let { playback.play(it, forcePicker = false) }
                }
            }
        },
        onToggleLibrary = viewModel::toggleLibrary,
        onClearProgress = viewModel::clearProgress,
        onRetry = viewModel::retry,
        onBack = onBack,
    )

    val background = MaterialTheme.colorScheme.background
    val media = state.media ?: item
    Box(Modifier.fillMaxSize().background(background).onFocusChanged { pageHasFocus = it.hasFocus }.focusGroup()) {
        SizedImage(url = media.heroBackdropUrl ?: media.backdropUrl, size = BackdropSize, contentDescription = null, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to background.copy(alpha = 0.92f), 0.42f to background.copy(alpha = 0.55f), 0.75f to Color.Transparent),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to background.copy(alpha = 0.7f))))
        // Top-aligned left column. Scrolls only when a short screen can't fit the stack, so a focused
        // button is never off screen.
        Column(
            Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 64.dp, top = 24.dp, bottom = 24.dp).width(LeftColumnWidth),
            verticalArrangement = Arrangement.spacedBy(ColumnSpacing),
        ) {
            TitleArt(media, logoSize = LogoSize, textStyle = MaterialTheme.typography.displaySmall)
            MediaMetadataLine(media, showCertification = true, maxGenres = MAX_GENRES)
            DetailsBody(state, media)
            // Laid out and focusable from frame 1 (focus leaves the covered page at once), but drawn only
            // once Play/Resume is final, so the first visible frame already reads `Resume S1:E5`.
            val actionsReady = state.actionsReady
            DetailsActionStack(actions, firstButton, Modifier.drawWithContent { if (actionsReady) drawContent() })
        }
        // Centred on the full screen height, outside the scroll, so it never moves with the left column.
        DetailsCastGrid(state.details?.credits.orEmpty(), Modifier.align(Alignment.CenterEnd).padding(end = 48.dp))
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
                Text(media.overview, style = MaterialTheme.typography.bodyLarge, maxLines = SYNOPSIS_LINES, overflow = TextOverflow.Ellipsis)
            }
            // Always present (blank without progress), so the note arriving or clearing never moves the stack.
            Text(state.resumeNoteSlot, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

private val BackdropSize = DpSize(960.dp, 540.dp)
private val LogoSize = DpSize(340.dp, 80.dp)
private const val MAX_GENRES = 2
private const val SYNOPSIS_LINES = 5
private val LeftColumnWidth = 560.dp
private val ColumnSpacing = 8.dp
