package com.seastreams.tv.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.CatalogRowState
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.ui.components.CardHeight
import com.seastreams.tv.ui.components.MetadataFormat
import com.seastreams.tv.ui.components.PosterWidth
import com.seastreams.tv.ui.components.RowGutter
import com.seastreams.tv.ui.components.RowTitle
import com.seastreams.tv.ui.theme.FocusPill
import com.seastreams.tv.ui.theme.Obsidian
import com.seastreams.tv.ui.theme.SurfaceCard
import com.seastreams.tv.ui.theme.TextSecondary

/** One vertical slot of the Home page, top to bottom. */
sealed interface HomeSection {
    val key: String

    /** Whether D-pad focus can land in this section. */
    val focusable: Boolean

    data object Hero : HomeSection {
        override val key = "hero"
        override val focusable = true
    }

    data class ContinueWatching(val items: List<ContinueWatchingItem>) : HomeSection {
        override val key = "continue-watching"
        override val focusable = true
    }

    data class Catalog(val spec: CatalogRowSpec, val content: RowContent) : HomeSection {
        override val key = "row-${spec.id}"
        override val focusable get() = content is RowContent.Loaded
    }
}

/**
 * Hero, Continue Watching (only when non-empty), then each catalog row. Rows that loaded but came
 * back empty (e.g. every title was above a Kids ceiling) are dropped instead of drawn empty.
 * The hero slot stays even while loading/failed so the page doesn't jump.
 */
fun buildHomeSections(state: HomeUiState): List<HomeSection> = buildList {
    add(HomeSection.Hero)
    if (state.continueWatching.isNotEmpty()) add(HomeSection.ContinueWatching(state.continueWatching))
    state.rows.forEach { row: CatalogRowState ->
        val content = row.content
        if (content is RowContent.Loaded && content.row.items.isEmpty()) return@forEach
        add(HomeSection.Catalog(row.spec, content))
    }
}

/** Non-focusable skeleton for a row that is still loading. */
@Composable
fun LoadingRow(title: String) {
    Column {
        RowTitle(title)
        Row(Modifier.padding(start = RowGutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(PLACEHOLDER_CARDS) {
                Box(Modifier.size(PosterWidth, CardHeight).clip(RoundedCornerShape(12.dp)).background(SurfaceCard))
            }
        }
    }
}

/** Non-focusable notice for a row whose list couldn't be fetched. */
@Composable
fun FailedRow(title: String) {
    Column {
        RowTitle(title)
        Text(
            "Couldn't load this row. Check your connection and reopen Home.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(start = RowGutter),
        )
    }
}

/** Progress bar plus `S7:E10` badge drawn on a Continue Watching card. */
@Composable
fun ContinueWatchingOverlay(item: ContinueWatchingItem) {
    Box(Modifier.fillMaxWidth().height(CardHeight)) {
        MetadataFormat.episodeBadge(item.seasonNumber, item.episodeNumber)?.let { badge ->
            Text(
                badge,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 12.dp)
                    .background(Obsidian.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(item.progress).height(4.dp).background(FocusPill))
        }
    }
}

private const val PLACEHOLDER_CARDS = 7
