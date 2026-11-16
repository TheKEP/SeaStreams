package com.seastreams.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.ui.theme.Obsidian

/** What a tab body needs from [TabScaffold] to drive focus between itself and the nav bar. */
class TabChrome(
    /** The selected tab's pill in the nav bar. */
    val tabPill: FocusRequester,
    private val pillFocused: () -> Boolean,
) {
    val isTabPillFocused: Boolean get() = pillFocused()
}

/**
 * Movies, TV Shows and Library: the [TopNavBar] with [tab] selected above the tab's content.
 *
 * `Back` from the content moves focus to the tab's pill; `Back` while the pill (or any nav pill
 * restored to it) is focused returns to Home, mirroring Home's own `Back` → nav bar rule (PRD §1).
 */
@Composable
fun TabScaffold(
    profile: ProfileEntity?,
    tab: NavTab,
    onTabSelected: (NavTab) -> Unit,
    onSwitchProfile: () -> Unit,
    content: @Composable ColumnScope.(TabChrome) -> Unit,
) {
    val tabPill = remember { FocusRequester() }
    var pillFocused by remember { mutableStateOf(false) }
    val chrome = remember(tabPill) { TabChrome(tabPill) { pillFocused } }
    BackHandler {
        if (pillFocused) onTabSelected(NavTab.HOME) else runCatching { tabPill.requestFocus() }
    }
    Column(Modifier.fillMaxSize().background(Obsidian)) {
        TopNavBar(
            profile = profile,
            selectedTab = tab,
            selectedTabFocusRequester = tabPill,
            onTabSelected = onTabSelected,
            onAvatarClick = onSwitchProfile,
            onSelectedTabFocusChanged = { pillFocused = it },
        )
        content(chrome)
    }
}
