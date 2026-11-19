package com.seastreams.tv.ui.screens.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.Episode
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.domain.model.TitleDetails
import com.seastreams.tv.ui.components.ActionMenu
import com.seastreams.tv.ui.components.ActionMenuRequest
import com.seastreams.tv.ui.components.HoverSidebar
import com.seastreams.tv.ui.components.LocalFocusOrigins
import com.seastreams.tv.ui.components.LocalTransientMessages
import com.seastreams.tv.ui.components.MediaMetadataLine
import com.seastreams.tv.ui.screens.playback.PlaybackViewModel
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.rememberListEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.verticalListEdgeWrap
import kotlinx.coroutines.launch

/**
 * Episodes (`episodes-page.jpg`): show title, metadata and hover-activated season pills on the left;
 * the selected season's episodes on the right. Focus lands on the next-up episode once its season
 * has loaded. `Left` from an episode returns to the selected season; `Right` from a season enters
 * the list. Both columns wrap both ways (§1): a fresh `Up` on the first season/episode goes to the
 * last, a fresh `Down` on the last goes to the first, and a held key stops at the edge. `Back`
 * (shell) returns to `More episodes` on Details.
 *
 * Holding `OK` on a season pill or an episode opens its action menu ([seasonMenu], [episodeMenu]);
 * `Back` closes the menu and refocuses the pill or episode that opened it.
 */
@Composable
fun EpisodesScreen(
    profileId: Long,
    details: TitleDetails,
    imdbId: String?,
    viewModel: EpisodesViewModel,
    playback: PlaybackViewModel,
) {
    LaunchedEffect(profileId, details.ref) { viewModel.open(profileId, details, imdbId) }
    val latest by viewModel.state.collectAsState()
    val state = if (latest.details?.ref == details.ref) latest else EpisodesUiState(profileId, details, imdbId)
    val selectedSeason = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { selectedSeason.requestFocusAfterFrame() }
    var menu by remember { mutableStateOf<ActionMenuRequest?>(null) }
    val origins = LocalFocusOrigins.current
    val messages = LocalTransientMessages.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) { viewModel.messages.collect { messages?.show(it) } }

    // Playback started from a menu returns focus to the menu's opener when the stream list closes.
    val play: (Episode, FocusRequester, Boolean, Boolean) -> Unit = { episode, from, forcePicker, forceAutoPlay ->
        viewModel.target(episode)?.let {
            origins?.mark(from)
            playback.play(it, forcePicker, forceAutoPlay)
        }
    }
    val openSeasonMenu: (SeasonInfo, FocusRequester) -> Unit = { season, from ->
        menu = seasonMenu(
            season = season,
            returnFocus = from,
            onMark = { watched -> viewModel.markSeason(season.number, watched) },
            onShuffle = {
                scope.launch {
                    val target = viewModel.shuffleTarget(season.number)
                    if (target == null) {
                        messages?.show("No episodes to shuffle yet")
                    } else {
                        origins?.mark(from)
                        playback.play(target, forcePicker = false)
                    }
                }
            },
        )
    }
    val openEpisodeMenu: (Episode, FocusRequester) -> Unit = { episode, from ->
        menu = episodeMenu(
            episode = episode,
            watched = viewModel.state.value.isWatched(episode.ref),
            returnFocus = from,
            onViewStreams = { play(episode, from, true, false) },
            onAutoPlay = { play(episode, from, false, true) },
            onToggleWatched = { viewModel.toggleWatched(episode) },
            onMarkPrevious = { watched -> viewModel.markThrough(episode, watched) },
        )
    }

    Box(Modifier.fillMaxSize()) {
        EpisodesContent(
            details = details,
            state = state,
            selectedSeason = selectedSeason,
            focusOnLoad = !initialFocusDone,
            onInitialFocus = { initialFocusDone = true },
            onSelectSeason = viewModel::selectSeason,
            onSeasonMenu = openSeasonMenu,
            onEpisodeMenu = openEpisodeMenu,
            onPlay = { episode -> viewModel.target(episode)?.let { playback.play(it, forcePicker = false) } },
        )
        menu?.let { request -> ActionMenu(request, onClose = { menu = null }) }
    }
}

@Composable
private fun EpisodesContent(
    details: TitleDetails,
    state: EpisodesUiState,
    selectedSeason: FocusRequester,
    focusOnLoad: Boolean,
    onInitialFocus: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onSeasonMenu: (SeasonInfo, FocusRequester) -> Unit,
    onEpisodeMenu: (Episode, FocusRequester) -> Unit,
    onPlay: (Episode) -> Unit,
) {
    Row(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 64.dp, top = 56.dp, end = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Column(Modifier.width(300.dp)) {
            Text(details.media.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            MediaMetadataLine(details.media.copy(genres = emptyList(), runtimeMinutes = null), showCertification = false)
            Text(seasonCountLabel(details.seasons), style = MaterialTheme.typography.titleSmall, color = TextSecondary)
            Spacer(Modifier.height(28.dp))
            if (details.seasons.isNotEmpty()) {
                HoverSidebar(
                    entries = details.seasons,
                    selected = details.seasons.firstOrNull { it.number == state.selectedSeason } ?: details.seasons.first(),
                    label = { it.displayName() },
                    trailing = { "${it.episodeCount} episodes" },
                    onSelect = { onSelectSeason(it.number) },
                    selectedFocusRequester = selectedSeason,
                    onLongClick = onSeasonMenu,
                    wrapUp = true,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            val season = details.seasons.firstOrNull { it.number == state.selectedSeason }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(season?.displayName().orEmpty(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                season?.let { Text("${it.episodeCount} Episodes", style = MaterialTheme.typography.titleSmall, color = TextSecondary) }
            }
            Spacer(Modifier.height(12.dp))
            // Keyed by season so each season's list starts at its own top with fresh focus memory.
            key(state.selectedSeason) {
                when (val episodes = state.selected) {
                    SeasonEpisodes.Loading -> Status("Loading episodes…")
                    SeasonEpisodes.Failed -> Status("Couldn't load this season")
                    is SeasonEpisodes.Loaded -> EpisodeList(
                        episodes = episodes.episodes,
                        state = state,
                        focusOnLoad = focusOnLoad,
                        onInitialFocus = onInitialFocus,
                        onPlay = onPlay,
                        onMenu = onEpisodeMenu,
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeList(
    episodes: List<Episode>,
    state: EpisodesUiState,
    focusOnLoad: Boolean,
    onInitialFocus: () -> Unit,
    onPlay: (Episode) -> Unit,
    onMenu: (Episode, FocusRequester) -> Unit,
) {
    val listState = rememberLazyListState()
    val first = remember { FocusRequester() }
    val last = remember { FocusRequester() }
    val nextUp = remember { FocusRequester() }
    val nextUpIndex = episodes.indexOfFirst { it.ref == state.nextUp }
    LaunchedEffect(Unit) {
        if (!focusOnLoad) return@LaunchedEffect
        if (nextUpIndex > 0) listState.scrollToItem(nextUpIndex)
        if (!(nextUpIndex >= 0 && nextUp.requestFocusAfterFrame())) first.requestFocusAfterFrame()
        onInitialFocus()
    }
    val wrap = rememberListEdgeWrap(listState, first, last, episodes.lastIndex)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().focusRestorer(first),
        contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(episodes, key = { _, episode -> episode.ref.episode }) { index, episode ->
            EpisodeRow(
                episode = episode,
                progress = state.progressOf(episode.ref),
                blurStill = state.blurUnwatched && state.isUnwatched(episode.ref),
                onPlay = { onPlay(episode) },
                onLongClick = { requester -> onMenu(episode, requester) },
                modifier = Modifier
                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                    .then(if (index == nextUpIndex) Modifier.focusRequester(nextUp) else Modifier)
                    .then(if (index == episodes.lastIndex) Modifier.focusRequester(last) else Modifier)
                    .verticalListEdgeWrap(index, episodes.lastIndex, wrap.toFirst, wrap.toLast),
            )
        }
    }
}

@Composable
private fun Status(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = TextSecondary)
    }
}

private fun seasonCountLabel(seasons: List<SeasonInfo>): String =
    if (seasons.size == 1) "1 Season" else "${seasons.size} Seasons"
