package com.seastreams.tv.ui.screens.details

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.ui.components.MetadataFormat
import com.seastreams.tv.ui.theme.DetailsOutlineMuted
import com.seastreams.tv.ui.theme.MetadataPillSurface
import com.seastreams.tv.ui.theme.TextPrimary
import com.seastreams.tv.ui.theme.TmdbGreen
import com.seastreams.tv.ui.theme.TmdbNavy

/** One pill in the Details metadata row (PRD §6). */
internal sealed interface DetailsBadge {
    /** Rendered as the split `TMDB | 81%` pill. */
    data class Rating(val percent: Int) : DetailsBadge
    data class Year(val year: Int) : DetailsBadge
    data class Certification(val label: String) : DetailsBadge
    data class Duration(val label: String) : DetailsBadge
    data class Genre(val name: String) : DetailsBadge
}

internal const val MAX_DETAILS_GENRES = 3

/**
 * The Details badges, left to right: TMDB rating → year → certification → duration → up to
 * [MAX_DETAILS_GENRES] genres. Missing or blank fields are skipped.
 */
internal fun detailsBadges(item: MediaItem): List<DetailsBadge> = buildList {
    item.ratingPercent?.let { add(DetailsBadge.Rating(it)) }
    item.year?.let { add(DetailsBadge.Year(it)) }
    item.certification?.trim()?.takeIf { it.isNotEmpty() }?.let { add(DetailsBadge.Certification(it)) }
    MetadataFormat.runtime(item.runtimeMinutes)?.let { add(DetailsBadge.Duration(it)) }
    item.genres.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_DETAILS_GENRES)
        .forEach { add(DetailsBadge.Genre(it)) }
}

/**
 * How many leading pills of [widths] fit completely on one row of [maxWidth], with [gap] before every
 * pill after the first. Stops at the first pill that doesn't fit, so later (genre) pills are dropped
 * rather than wrapped or clipped.
 */
internal fun fittingBadgeCount(widths: List<Int>, gap: Int, maxWidth: Int): Int {
    var used = 0
    widths.forEachIndexed { index, width ->
        val next = used + (if (index == 0) 0 else gap) + width
        if (next > maxWidth) return index
        used = next
    }
    return widths.size
}

/**
 * `[TMDB|81%] [2026] [PG] [1h 49m] [Comedy] [Adventure] [Family]` as uniform rounded pills on a strict
 * single row: trailing genres that don't fit completely in the left column are omitted (never wrapped).
 * Purely decorative: never focusable, so the action stack's D-pad map is unchanged.
 */
@Composable
fun DetailsMetadataBadges(item: MediaItem, modifier: Modifier = Modifier) {
    val badges = detailsBadges(item)
    if (badges.isEmpty()) return
    Layout(
        modifier = modifier.fillMaxWidth().focusProperties { canFocus = false },
        content = { badges.forEach { BadgePill(it) } },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val gap = BadgeSpacing.roundToPx()
        val shown = if (constraints.hasBoundedWidth) {
            fittingBadgeCount(placeables.map { it.width }, gap, constraints.maxWidth)
        } else {
            placeables.size
        }
        val visible = placeables.take(shown)
        val rowHeight = visible.maxOfOrNull { it.height } ?: 0
        val rowWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else visible.sumOf { it.width } + gap * (visible.size - 1).coerceAtLeast(0)
        layout(rowWidth, rowHeight.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var x = 0
            visible.forEach { pill ->
                pill.placeRelative(x, (rowHeight - pill.height) / 2)
                x += pill.width + gap
            }
        }
    }
}

@Composable
private fun BadgePill(badge: DetailsBadge) {
    when (badge) {
        is DetailsBadge.Rating -> TmdbRatingBadge(badge.percent)
        is DetailsBadge.Year -> MetadataPill(badge.year.toString())
        is DetailsBadge.Certification -> MetadataPill(badge.label)
        is DetailsBadge.Duration -> MetadataPill(badge.label)
        is DetailsBadge.Genre -> MetadataPill(badge.name)
    }
}

@Composable
private fun TmdbRatingBadge(percent: Int) {
    Row(
        Modifier
            .height(BadgeHeight)
            .clip(BadgeShape)
            .border(BorderStroke(1.dp, TmdbGreen), BadgeShape)
            .semantics(mergeDescendants = true) { contentDescription = "TMDB rating $percent%" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BadgeSegment(TmdbGreen) {
            Text(
                "TMDB",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp,
                color = TmdbNavy,
                maxLines = 1,
            )
        }
        BadgeSegment(TmdbNavy) {
            Text("$percent%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1)
        }
    }
}

@Composable
private fun BadgeSegment(fill: Color, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxHeight().background(fill).padding(horizontal = SegmentPadding),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun MetadataPill(label: String) {
    Box(
        Modifier
            .height(BadgeHeight)
            .clip(BadgeShape)
            .background(MetadataPillSurface)
            .border(BorderStroke(1.dp, DetailsOutlineMuted), BadgeShape)
            .padding(horizontal = PillPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = DetailsOutlineMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val BadgeHeight = 22.dp
private val BadgeShape = RoundedCornerShape(5.dp)
private val BadgeSpacing = 6.dp
private val PillPadding = 7.dp
private val SegmentPadding = 6.dp
