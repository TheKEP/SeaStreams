package com.seastreams.tv.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.CatalogRowState
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.ui.components.ArtworkProgressIndicator
import com.seastreams.tv.ui.components.CardHeight
import com.seastreams.tv.ui.components.MetadataFormat
import com.seastreams.tv.ui.components.RowRestoreStep
import com.seastreams.tv.ui.components.artworkProgress
import com.seastreams.tv.ui.components.resolveRowRestore
import com.seastreams.tv.ui.theme.Obsidian

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
        override val key = KEY
        override val focusable = true

        companion object {
            const val KEY = "continue-watching"
        }
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

/** Where Home's page must go to return focus to catalog row [rowId] (back from its View More). */
fun homeRowRestore(sections: List<HomeSection>, rowId: String): RowRestoreStep {
    val index = sections.indexOfFirst { it is HomeSection.Catalog && it.spec.id == rowId }
    return resolveRowRestore(index, (sections.getOrNull(index) as? HomeSection.Catalog)?.content)
}

/**
 * `S7:E10` badge plus the shared watch indicator on a Continue Watching card: the bottom bar while
 * in progress (every row here is `< 90%` by query), never a full-width "finished" line.
 */
@Composable
fun ContinueWatchingOverlay(item: ContinueWatchingItem) {
    Box(Modifier.fillMaxWidth().height(CardHeight)) {
        ArtworkProgressIndicator(artworkProgress(item.progress))
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
    }
}

