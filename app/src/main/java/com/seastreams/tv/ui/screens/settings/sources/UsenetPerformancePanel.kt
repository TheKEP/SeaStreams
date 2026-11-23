package com.seastreams.tv.ui.screens.settings.sources

import androidx.compose.runtime.Composable
import com.seastreams.tv.ui.screens.settings.PanelItem
import com.seastreams.tv.ui.screens.settings.SectionedRowsPanel

/** `Usenet performance` (PRD §12): section labels over stepper / toggle rows, opening on `Performance profile`. */
@Composable
fun UsenetPerformancePanel(items: List<PanelItem>) {
    SectionedRowsPanel("Usenet performance", items)
}
