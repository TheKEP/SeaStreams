package com.seastreams.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.ui.theme.SuccessGreen
import com.seastreams.tv.ui.theme.TextPrimary
import com.seastreams.tv.ui.theme.TextSecondary

/**
 * `👍 74%  2026  Comedy  [PG-13]  1h 47m` — shared by the hero, the expanded-card info panel and the
 * Episodes header (Details uses its own pill row, `DetailsMetadataBadges`).
 * Missing fields are simply skipped.
 */
@Composable
fun MediaMetadataLine(
    item: MediaItem,
    showCertification: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleSmall,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        item.ratingPercent?.let { percent ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.ThumbUp, contentDescription = "Rating", tint = SuccessGreen, modifier = Modifier.size(16.dp))
                Text("$percent%", style = style, color = SuccessGreen, fontWeight = FontWeight.Bold)
            }
        }
        item.year?.let { Text(it.toString(), style = style, fontWeight = FontWeight.SemiBold) }
        item.genres.take(1).forEach { Text(it, style = style, fontWeight = FontWeight.SemiBold) }
        if (showCertification) item.certification?.takeIf { it.isNotBlank() }?.let { CertificationBadge(it) }
        MetadataFormat.runtime(item.runtimeMinutes)?.let { Text(it, style = style, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun CertificationBadge(label: String) {
    Box(
        Modifier
            .border(BorderStroke(1.dp, TextSecondary), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextPrimary)
    }
}

/** Title logo when TMDB has one, otherwise the title as bold text. */
@Composable
fun TitleArt(item: MediaItem, logoSize: DpSize, textStyle: TextStyle, modifier: Modifier = Modifier) {
    if (item.logoUrl != null) {
        SizedImage(
            url = item.logoUrl,
            size = logoSize,
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            modifier = modifier.size(logoSize),
        )
    } else {
        Text(
            item.title,
            style = textStyle,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}
