package com.seastreams.tv.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.seastreams.tv.R

/** Brand wordmark typeface (startup splash), bundled as `res/font/fredoka_semibold.ttf`. */
val FredokaFontFamily = FontFamily(Font(R.font.fredoka_semibold, FontWeight.SemiBold))

/**
 * Catalog row typeface (`trending_movies_catalog_font.jpg`), bundled as `res/font/montserrat_*.ttf`
 * (SIL OFL 1.1, `docs/licenses/Montserrat-OFL.txt`). Used only by catalog rows; the rest of the app
 * keeps the default MaterialTheme typography.
 */
val MontserratFontFamily = FontFamily(
    Font(R.font.montserrat_medium, FontWeight.Medium),
    Font(R.font.montserrat_semibold, FontWeight.SemiBold),
    Font(R.font.montserrat_bold, FontWeight.Bold),
)

/** Catalog row text styles (PRD §4 Catalog Row Typography), matched to `trending_movies_catalog_font.jpg`. */
object CatalogTypography {
    /** Row header, e.g. `Trending Movies`. */
    val rowHeader = TextStyle(
        fontFamily = MontserratFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        color = TextPrimary,
    )

    /** Title inside the 16:9 showcase slot when TMDB has no logo for it. */
    val slotFallbackTitle = TextStyle(
        fontFamily = MontserratFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
        color = TextPrimary,
    )

    /** `👍 70%  2026  Action  2h 20m` under the slot (the rating keeps its green; MediaMetadataLine sets SemiBold/Bold). */
    val metadata = TextStyle(
        fontFamily = MontserratFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    )

    /** Up-to-3-line synopsis under the metadata line, near-white as in the reference. */
    val synopsis = TextStyle(
        fontFamily = MontserratFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
        color = TextPrimary.copy(alpha = SYNOPSIS_ALPHA),
    )
}

private const val SYNOPSIS_ALPHA = 0.9f
