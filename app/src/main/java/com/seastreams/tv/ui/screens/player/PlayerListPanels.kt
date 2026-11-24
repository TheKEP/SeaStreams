package com.seastreams.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.ui.components.MetadataFormat
import com.seastreams.tv.ui.theme.SurfaceCard
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.focusPillColors
import com.seastreams.tv.ui.theme.requestFocusAfterFrame

/**
 * Episodes overlay: season pills across the top, the season's aired episodes below. Focus starts on
 * the playing episode's season; choosing an episode plays its top stream without leaving the player.
 */
@Composable
fun EpisodesPanel(state: PlayerUiState, viewModel: PlayerViewModel, modifier: Modifier) {
    val browser = state.episodes
    val seasonPill = remember { FocusRequester() }
    LaunchedEffect(Unit) { seasonPill.requestFocusAfterFrame() }
    Column(
        modifier.padding(bottom = PANEL_BOTTOM_INSET).fillMaxHeight().width(PanelWidth + 80.dp).background(SurfaceCard.copy(alpha = 0.94f))
            .padding(horizontal = 24.dp, vertical = 28.dp).trapPanelFocus(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Episodes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(browser.seasons, key = { it.number }) { season ->
                val selected = season.number == browser.season
                Surface(
                    onClick = { viewModel.selectSeason(season.number) },
                    modifier = if (selected) Modifier.focusRequester(seasonPill) else Modifier,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                    colors = focusPillColors(containerColor = if (selected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f)),
                ) {
                    Text(season.name.ifBlank { "Season ${season.number}" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (browser.loading) item(key = "loading") { Text("Loading episodes…", color = TextSecondary) }
            items(browser.episodes, key = { "${it.ref.season}-${it.ref.episode}" }) { episode ->
                val badge = MetadataFormat.episodeBadge(episode.ref.season, episode.ref.episode).orEmpty()
                val runtime = episode.runtimeMinutes?.let(MetadataFormat::runtime)
                OptionRow(
                    label = "$badge  ${episode.title}",
                    detail = listOfNotNull(runtime, episode.overview.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null },
                    selected = episode.ref == browser.playing,
                    onClick = { viewModel.playEpisode(episode.ref) },
                )
            }
        }
    }
}
