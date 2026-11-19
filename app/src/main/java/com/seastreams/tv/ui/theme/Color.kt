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

/** The stream list's `LAST USED` pill (`last_used.png`): Obsidian text on gold, focused or not. */
val LastUsedGold = Color(0xFFF5C518)
