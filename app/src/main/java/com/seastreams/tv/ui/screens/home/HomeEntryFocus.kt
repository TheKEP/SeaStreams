package com.seastreams.tv.ui.screens.home

/** Where focus lands when Home is (re)entered. */
internal enum class HomeEntryFocus {
    /** The nav bar's `Home` pill: first load and every switch back from another tab. Never the hero. */
    HOME_PILL,

    /** Back from a row's View More: that row's first card ([com.seastreams.tv.ui.components.RestoreRowFocusEffect]). */
    RETURN_ROW,
}

/** Home's entry focus (PRD §3): the `Home` pill unless a View More return names a row to restore. */
internal fun homeEntryFocus(returnRowId: String?): HomeEntryFocus =
    if (returnRowId == null) HomeEntryFocus.HOME_PILL else HomeEntryFocus.RETURN_ROW
