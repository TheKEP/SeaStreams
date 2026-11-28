package com.seastreams.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.ui.theme.CatalogTypography

/**
 * Metadata line (20) + 4dp gap + 3-line synopsis (3 × 17 = 51), inside a fixed 88dp. With the header,
 * 218dp artwork and [InfoTopPadding], a focused row keeps the same height as before, so on a
 * 540dp-tall screen the next row's title and the top of its posters still show below it.
 */
private val InfoHeight = 88.dp
private val InfoTopPadding = 6.dp
private val InfoWidth = 560.dp
private val InfoGap = 4.dp
private const val SYNOPSIS_MAX_LINES = 3

/** A catalog row header (e.g. `Trending Movies`) in [CatalogTypography.rowHeader]. */
@Composable
fun RowTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = CatalogTypography.rowHeader,
        modifier = modifier.padding(start = RowGutter, bottom = 8.dp),
    )
}

/**
 * The block under a row's showcase slot (`trending_movies_catalog_font.jpg`): `👍 70%  2026  Action  2h 20m`
 * and an up-to-3-line near-white synopsis. There is no title line: the slot's logo (or fallback title)
 * already names the item. Fixed height, so moving between cards (or onto View More) never shifts the
 * rows below; [item] `null` keeps the space empty.
 */
@Composable
internal fun CatalogRowInfo(item: MediaItem?) {
    Box(Modifier.padding(start = RowGutter, top = InfoTopPadding).height(InfoHeight)) {
        if (item != null) {
            Column(Modifier.width(InfoWidth), verticalArrangement = Arrangement.spacedBy(InfoGap)) {
                MediaMetadataLine(item, showCertification = false, style = CatalogTypography.metadata)
                if (item.overview.isNotBlank()) {
                    Text(
                        item.overview,
                        style = CatalogTypography.synopsis,
                        maxLines = SYNOPSIS_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
