package com.seastreams.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.ui.components.MetadataFormat
import com.seastreams.tv.ui.components.RowEntry
import com.seastreams.tv.ui.components.rowEntry
import com.seastreams.tv.ui.theme.SurfaceCard
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.EdgeKeyAction
import com.seastreams.tv.ui.theme.asRevealList
import com.seastreams.tv.ui.theme.edgeKeyAction
import com.seastreams.tv.ui.theme.focusItem
import com.seastreams.tv.ui.theme.focusPillColors
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.rememberListEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.revealItem
import kotlinx.coroutines.launch

/**
 * Episodes overlay (PRD §9): season pills across the top, the season's aired episodes below. It opens on the playing
 * episode's season with that pill scrolled into view and focused; once the list loads, focus moves to the playing
 * episode (scrolled into view) unless the user already pressed a key. `Up` from the episode list always lands on the
 * highlighted season pill, scrolling the pill row first when it is off-screen.
 *
 * Edge-stop-then-wrap (PRD §1), held keys always stop on the edge: the vertical loop runs `Episode 1` —`Up`→ season
 * pill —`Up`→ last episode —`Down`→ `Episode 1` (a fresh `Down` on the last episode never jumps to the season row; `Up`
 * on a pill while the list is loading or empty is consumed), and the season row wraps first ⇄ last pill. The season
 * row spans the full card width with the card's side padding as content padding, so edge pills and their focus
 * ring are never clipped. The card ends [bottomInset] above the
 * screen bottom (the same edge as Subtitles/Audio/Speed/Info) and shows the shared [PanelScrollbar] while the
 * episodes overflow. Choosing an episode plays its top stream without leaving the player.
 */
@Composable
fun EpisodesPanel(state: PlayerUiState, viewModel: PlayerViewModel, bottomInset: Dp, modifier: Modifier) {
    val browser = state.episodes
    val scope = rememberCoroutineScope()
    val seasonPill = remember { FocusRequester() }
    val playingRow = remember { FocusRequester() }
    val firstRow = remember { FocusRequester() }
    val lastRow = remember { FocusRequester() }
    val firstSeason = remember { FocusRequester() }
    val lastSeason = remember { FocusRequester() }
    val seasonList = rememberLazyListState()
    val episodeList = rememberLazyListState()
    // Any key press inside the card means the user is driving focus: the late "jump to the playing episode" is skipped.
    var userMoved by remember { mutableStateOf(false) }
    var episodeFocusDone by remember { mutableStateOf(false) }
    val seasonIndex = seasonPillIndex(browser)
    val lastSeasonIndex = browser.seasons.lastIndex
    val episodeWrap = rememberListEdgeWrap(episodeList, firstRow, lastRow, browser.episodes.lastIndex)
    // Fresh `Up` on any season pill: the listed season's last episode; consumed with nothing to land on.
    val seasonUpWrap: () -> Unit = { if (lastEpisodeIndex(browser) != null) episodeWrap.toLast() }
    LaunchedEffect(Unit) {
        if (seasonIndex >= 0) seasonList.asRevealList().revealItem(seasonIndex)
        seasonPill.requestFocusAfterFrame()
    }
    val focusIndex = episodeFocusIndex(browser)
    LaunchedEffect(focusIndex) {
        if (episodeFocusDone || focusIndex == null) return@LaunchedEffect
        episodeFocusDone = true
        if (userMoved) return@LaunchedEffect
        if (!episodeList.focusItem(focusIndex, playingRow)) episodeList.focusItem(0, firstRow)
    }
    Column(
        modifier
            .padding(bottom = bottomInset)
            .fillMaxHeight()
            .width(PanelWidth + EPISODES_EXTRA_WIDTH)
            .background(SurfaceCard.copy(alpha = 0.94f))
            // Side padding lives on the children, so the season row clips at the card edge instead (see its contentPadding).
            .padding(top = 28.dp, bottom = 20.dp)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) userMoved = true
                false
            }
            .trapPanelFocus(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Episodes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = CARD_START, end = CARD_END))
        LazyRow(
            state = seasonList,
            modifier = Modifier.fillMaxWidth().focusProperties {
                onEnter = {
                    if (seasonIndex >= 0) {
                        val visible = seasonList.layoutInfo.visibleItemsInfo.any { it.index == seasonIndex }
                        when (rowEntry(requestedFocusDirection, visible)) {
                            RowEntry.DEFAULT -> Unit
                            RowEntry.FOCUS_LAST -> runCatching { seasonPill.requestFocus() }
                            RowEntry.SCROLL_THEN_FOCUS_LAST -> {
                                cancelFocusChange()
                                scope.launch { focusSeasonPill(seasonList, seasonIndex, seasonPill) }
                            }
                        }
                    }
                }
            },
            contentPadding = PaddingValues(start = CARD_START, end = CARD_END, top = SEASON_RING_CLEARANCE, bottom = SEASON_RING_CLEARANCE),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(browser.seasons, key = { _, season -> season.number }) { index, season ->
                val selected = season.number == browser.season
                val edges = seasonPillEdgeKeys(index, lastSeasonIndex)
                Surface(
                    onClick = { viewModel.selectSeason(season.number) },
                    modifier = Modifier
                        .then(if (selected) Modifier.focusRequester(seasonPill) else Modifier)
                        .then(if (index == 0) Modifier.focusRequester(firstSeason) else Modifier)
                        .then(if (index == lastSeasonIndex) Modifier.focusRequester(lastSeason) else Modifier)
                        .then(if (Key.DirectionUp in edges) Modifier.onDpadEdgeWrap(Key.DirectionUp, seasonUpWrap) else Modifier)
                        .then(
                            if (Key.DirectionLeft in edges) {
                                Modifier.onDpadEdgeWrap(Key.DirectionLeft) { scope.launch { focusSeasonPill(seasonList, lastSeasonIndex, lastSeason) } }
                            } else {
                                Modifier
                            },
                        )
                        .then(
                            if (Key.DirectionRight in edges) {
                                Modifier.onDpadEdgeWrap(Key.DirectionRight) { scope.launch { focusSeasonPill(seasonList, 0, firstSeason) } }
                            } else {
                                Modifier
                            },
                        ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                    colors = focusPillColors(containerColor = if (selected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f)),
                ) {
                    Text(season.name.ifBlank { "Season ${season.number}" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
        Row(Modifier.weight(1f).padding(start = CARD_START, end = CARD_END), horizontalArrangement = Arrangement.spacedBy(SCROLLBAR_GAP)) {
            LazyColumn(
                state = episodeList,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (browser.loading) item(key = "loading") { Text("Loading episodes…", color = TextSecondary) }
                itemsIndexed(browser.episodes, key = { _, episode -> "${episode.ref.season}-${episode.ref.episode}" }) { index, episode ->
                    val badge = MetadataFormat.episodeBadge(episode.ref.season, episode.ref.episode).orEmpty()
                    val runtime = episode.runtimeMinutes?.let(MetadataFormat::runtime)
                    OptionRow(
                        label = "$badge  ${episode.title}",
                        detail = listOfNotNull(runtime, episode.overview.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null },
                        selected = episode.ref == browser.playing,
                        onClick = { viewModel.playEpisode(episode.ref) },
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(firstRow).holdFirstEpisodeUp() else Modifier)
                            .then(if (index == focusIndex) Modifier.focusRequester(playingRow) else Modifier)
                            .then(
                                if (index == browser.episodes.lastIndex) {
                                    Modifier.focusRequester(lastRow).onDpadEdgeWrap(Key.DirectionDown, episodeWrap.toFirst)
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
            // Always laid out (so rows never reflow); drawn only while the episodes overflow.
            PanelScrollbar(episodeList::scrollMetrics, Modifier.fillMaxHeight().width(SCROLLBAR_WIDTH))
        }
    }
}

private suspend fun focusSeasonPill(seasonList: LazyListState, index: Int, pill: FocusRequester) {
    seasonList.asRevealList().revealItem(index)
    pill.requestFocusAfterFrame()
}

/** A held `Up` stops on episode 1; a fresh one passes on to the season row (its `onEnter` picks the highlighted pill). */
private fun Modifier.holdFirstEpisodeUp(): Modifier = onPreviewKeyEvent { event ->
    event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && holdsFirstEpisodeUp(event.nativeKeyEvent.repeatCount)
}

/** Whether `Up` on the first episode is a held auto-repeat, consumed so focus stops there (PRD §1). */
internal fun holdsFirstEpisodeUp(repeatCount: Int): Boolean = edgeKeyAction(repeatCount) == EdgeKeyAction.HOLD

/**
 * The outward keys season pill [index] of a row ending at [lastIndex] owns: `Up` on every pill (wraps to the last
 * episode), `Left` on the first (wraps to the last pill), `Right` on the last (wraps to the first); both on a lone pill.
 */
internal fun seasonPillEdgeKeys(index: Int, lastIndex: Int): Set<Key> = buildSet {
    if (lastIndex < 0 || index !in 0..lastIndex) return@buildSet
    add(Key.DirectionUp)
    if (index == 0) add(Key.DirectionLeft)
    if (index == lastIndex) add(Key.DirectionRight)
}

/** The listed season's last episode (the season-pill `Up` wrap target); `null` while loading or empty. */
internal fun lastEpisodeIndex(browser: EpisodeBrowser): Int? =
    browser.episodes.lastIndex.takeIf { !browser.loading && it >= 0 }

/** The highlighted (listed) season's position in the pill row, `-1` when it isn't one of the show's seasons. */
internal fun seasonPillIndex(browser: EpisodeBrowser): Int = browser.seasons.indexOfFirst { it.number == browser.season }

/**
 * The episode row focus moves to once the listed season has loaded: the playing episode when it is in that season,
 * else the first episode. `null` while loading or empty (the season pill keeps focus).
 */
internal fun episodeFocusIndex(browser: EpisodeBrowser): Int? {
    if (browser.loading || browser.episodes.isEmpty()) return null
    val playing = browser.playing?.takeIf { it.season == browser.season } ?: return 0
    return browser.episodes.indexOfFirst { it.ref == playing }.takeIf { it >= 0 } ?: 0
}

private val EPISODES_EXTRA_WIDTH = 80.dp
private val CARD_START = 24.dp
private val CARD_END = 16.dp

/** Room above/below the season pills for their 1.04x focus scale and ring. */
private val SEASON_RING_CLEARANCE = 4.dp
