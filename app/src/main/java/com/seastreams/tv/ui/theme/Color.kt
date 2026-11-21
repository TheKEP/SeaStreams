package com.seastreams.tv.ui.theme

import androidx.compose.ui.graphics.Color

// PRD §1 palette.
/** Default page background (PRD §1: `#000000`); the live one is `MaterialTheme.colorScheme.background`. */
val DefaultBackground = Color(0xFF000000)

/** Dark ink for scrims over artwork and for text on light focus pills. */
val Obsidian = Color(0xFF0B0D12)
val SurfaceCard = Color(0xFF161A23)
val SurfaceRaised = Color(0xFF1F2430)
val OutlineDim = Color(0xFF2A3040)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFA0A8B8)
val FocusPill = Color(0xFFE5E9F0)
val OnFocusPill = Obsidian
val SuccessGreen = Color(0xFF3DD68C)
val ErrorRed = Color(0xFFFF6B6B)

// PRD §9 player seek bar: opaque fills with hue + luminance separation, so bright video behind
// the HUD can't wash the tiers together.
/** Played portion (0 → playhead), drawn on top of the buffered fill: pure white. */
val SeekPlayed = TextPrimary
/** Buffered read-ahead (to the buffer end): opaque brand wave blue. */
val SeekBuffered = Color(0xFF2B6CB0)
/** Unbuffered track: opaque dark slate. */
val SeekTrack = Color(0xFF1E2430)
/** 1dp backing outline around the track and the playhead tick, for bright scenes. */
val SeekTrackBorder = Obsidian
/** Focused seek-bar thumb fill. */
val SeekThumb = FocusPill
/** Thumb outline so it stays legible over the white played fill. */
val SeekThumbRing = Obsidian

// PRD §1 Artwork Progress Bar (Continue Watching / artwork tiles): its own tokens, so the player seek
// bar keeps its slate / blue buffered / white played tiers.
/** Unwatched track: lighter slate grey, clearly separated from both the Obsidian backing and the white fill. */
val ArtworkProgressTrack = Color(0xFF4A5263)
/** Watched portion: crisp white. */
val ArtworkProgressWatched = TextPrimary
/** 1dp backing outline so the bar never washes out over bright posters and backdrops. */
val ArtworkProgressBorder = Obsidian

/** Details TMDB rating pill (PRD §6): TMDB user-score green + TMDB dark navy. */
val TmdbGreen = Color(0xFF21D07A)
val TmdbNavy = Color(0xFF081C22)
/** Details Year / Certification / Duration / Genre pills (PRD §6): smoked 48% surface. */
val MetadataPillSurface = SurfaceCard.copy(alpha = 0.48f)
/**
 * Muted cool off-white (`#727B8C`) for the Details non-TMDB pills' outline + text and the Cast card outline.
 * Deliberately deeper than [TextSecondary], which reads near-white on TV panels; [TextSecondary] itself stays global.
 */
val DetailsOutlineMuted = Color(0xFF727B8C)

/** The stream list's `LAST USED` pill (`last_used.png`): Obsidian text on gold, focused or not. */
val LastUsedGold = Color(0xFFF5C518)
