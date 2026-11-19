package com.seastreams.tv.ui.screens.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.CreditPerson
import com.seastreams.tv.ui.components.SizedImage
import com.seastreams.tv.ui.theme.DetailsOutlineMuted
import com.seastreams.tv.ui.theme.SurfaceCard
import com.seastreams.tv.ui.theme.TextPrimary
import com.seastreams.tv.ui.theme.TextSecondary

/**
 * Right-side `Cast` card on Details (PRD §6): the director (or a show's creator) then the top 9 billed
 * cast, 2 columns filled row-by-row (Director | Cast #1, Cast #2 | Cast #3, …), inside a translucent
 * rounded card tinted with the active background colour; the caller centres it vertically on screen.
 * Portrait styling follows `cast-images.jpg`.
 * Purely decorative: nothing here is focusable, so D-pad focus stays on the action stack. Draws
 * nothing when [people] is empty.
 */
@Composable
fun DetailsCastGrid(people: List<CreditPerson>, modifier: Modifier = Modifier) {
    if (people.isEmpty()) return
    Column(
        modifier
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.background.copy(alpha = CARD_TINT_ALPHA))
            .border(1.dp, DetailsOutlineMuted, CardShape)
            .padding(horizontal = CardPaddingHorizontal, vertical = CardPaddingVertical),
        verticalArrangement = Arrangement.spacedBy(HeaderGap),
    ) {
        Text(
            "Cast",
            style = MaterialTheme.typography.titleSmall.copy(lineHeight = 20.sp, shadow = LabelShadow),
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(GridWidth),
        )
        Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
            people.chunked(COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(ColumnGap)) {
                    row.forEach { CastPortrait(it) }
                }
            }
        }
    }
}

/** One fixed-height cell: ringed circular headshot over an initials disc, then a left-aligned name/role block centred beneath. */
@Composable
private fun CastPortrait(person: CreditPerson) {
    val labelStyle = MaterialTheme.typography.labelSmall.copy(shadow = LabelShadow)
    Column(
        Modifier.width(CellWidth).height(CellHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(PortraitSize)
                .clip(CircleShape)
                .background(SurfaceCard)
                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials(person.name), style = MaterialTheme.typography.labelLarge, color = TextSecondary, fontWeight = FontWeight.SemiBold)
            if (person.profileUrl != null) {
                SizedImage(
                    url = person.profileUrl,
                    size = PortraitImageSize,
                    contentDescription = person.name,
                    modifier = Modifier.size(PortraitSize),
                )
            }
        }
        Spacer(Modifier.height(LabelGap))
        Column(Modifier.widthIn(max = LabelMaxWidth), horizontalAlignment = Alignment.Start) {
            Text(
                person.name,
                style = labelStyle.copy(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Always present (blank when TMDB has no character) so every cell keeps the same height.
            Text(
                person.role.orEmpty(),
                style = labelStyle.copy(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Normal),
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** `Jill Culton` -> `JC`, `Zendaya` -> `Z`. */
internal fun initials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val first = words.firstOrNull()?.first() ?: return ""
    val last = words.drop(1).lastOrNull()?.first()
    return listOfNotNull(first, last).joinToString("").uppercase()
}

private const val COLUMNS = 2
private const val CARD_TINT_ALPHA = 0.50f
private val CardShape = RoundedCornerShape(16.dp)
private val CardPaddingHorizontal = 16.dp
private val CardPaddingVertical = 14.dp
private val CellWidth = 104.dp
private val ColumnGap = 22.dp
private val RowGap = 16.dp
private val GridWidth = CellWidth * COLUMNS + ColumnGap
private val CellHeight = 74.dp
private val PortraitSize = 44.dp
private val PortraitImageSize = DpSize(PortraitSize, PortraitSize)
private val LabelGap = 4.dp
private val LabelMaxWidth = 96.dp
private val HeaderGap = 8.dp
private val LabelShadow = Shadow(color = Color.Black.copy(alpha = 0.75f), offset = Offset(0f, 1f), blurRadius = 3f)
