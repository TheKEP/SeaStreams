package com.seastreams.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.ui.theme.SurfaceCard
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import kotlinx.coroutines.launch

/**
 * Info overlay: live technical telemetry, refreshed every second while open. It reaches down to just above the HUD's
 * seek row ([bottomInset], see [cardBottomInset]) and shows the shared [PanelScrollbar] while its rows overflow.
 */
@Composable
fun InfoPanel(viewModel: PlayerViewModel, bottomInset: Dp, modifier: Modifier) {
    val rows by viewModel.info.collectAsState()
    val holder = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { holder.requestFocusAfterFrame() }
    Column(
        modifier.padding(bottom = bottomInset).fillMaxHeight().width(PanelWidth).background(SurfaceCard.copy(alpha = 0.94f))
            .padding(start = 28.dp, end = 16.dp, top = 28.dp, bottom = 20.dp)
            // Up/Down scroll the rows (they don't fit a 540 dp screen above the bar); focus stays here.
            .onKeyEvent { event ->
                val step = when (event.key) {
                    Key.DirectionUp -> -INFO_SCROLL_STEP
                    Key.DirectionDown -> INFO_SCROLL_STEP
                    else -> return@onKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(step) }
                true
            }
            .focusRequester(holder).focusable().trapPanelFocus(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Info", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(SCROLLBAR_GAP)) {
            Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(row.label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.width(150.dp))
                        Text(row.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // Always laid out (so rows never reflow); drawn only while the rows overflow.
            PanelScrollbar(scroll::scrollMetrics, Modifier.fillMaxHeight().width(SCROLLBAR_WIDTH))
        }
    }
}

/** One D-pad press scrolls the Info rows by about two rows. */
private const val INFO_SCROLL_STEP = 120f
