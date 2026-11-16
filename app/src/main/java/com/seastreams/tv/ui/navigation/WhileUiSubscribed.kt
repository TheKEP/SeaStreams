package com.seastreams.tv.ui.navigation

import kotlinx.coroutines.flow.SharingStarted

/**
 * Sharing policy for every screen's `uiState`: upstream keeps running for 5s after the last
 * collector leaves, so a configuration change or a quick back-and-forth doesn't restart loads.
 */
val WhileUiSubscribed: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L)
