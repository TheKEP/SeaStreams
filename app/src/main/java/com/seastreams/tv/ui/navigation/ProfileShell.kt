package com.seastreams.tv.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.ui.components.NavTab
import com.seastreams.tv.ui.components.ReturnRowFocus
import com.seastreams.tv.ui.components.RowReturnTarget
import com.seastreams.tv.ui.components.rowIdFor
import com.seastreams.tv.ui.screens.catalog.MoviesScreen
import com.seastreams.tv.ui.screens.catalog.TvShowsScreen
import com.seastreams.tv.ui.screens.catalog.ViewMoreScreen
import com.seastreams.tv.ui.screens.home.HomeDestination
import com.seastreams.tv.ui.screens.home.HomeScreen
import com.seastreams.tv.ui.screens.home.HomeViewModel
import com.seastreams.tv.ui.screens.library.LibraryScreen
import com.seastreams.tv.ui.screens.search.SearchScreen

/** Full-screen pages opened on top of the current tab; `Back` closes the top one. */
sealed interface Overlay {
    data object Search : Overlay

    data class ViewMore(val title: String, val source: CatalogSource) : Overlay
}

/** The tab a nav-bar pick switches to, or `null` when the pick isn't a tab of its own. */
internal fun tabFor(selected: NavTab): NavTab? = when (selected) {
    NavTab.HOME, NavTab.MOVIES, NavTab.TV_SHOWS, NavTab.LIBRARY -> selected
    NavTab.SEARCH, NavTab.SETTINGS -> null
}

/**
 * Everything after a profile is chosen: the Home / Movies / TV Shows / Library tabs, plus Search and
 * View More pages stacked over them. Each tab keeps its saveable state (e.g. scroll position) while
 * another tab or page is shown.
 */
@Composable
fun ProfileShell(profileId: Long, homeViewModel: HomeViewModel, onSwitchProfile: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(NavTab.HOME) }
    var overlays by remember { mutableStateOf(emptyList<Overlay>()) }
    val tabStates = rememberSaveableStateHolder()
    // The row whose View More is open, kept while that page is popped so its first card regains focus.
    var rowReturn by remember { mutableStateOf<RowReturnTarget?>(null) }

    val push: (Overlay) -> Unit = { overlays = overlays + it }
    val pop: () -> Unit = { overlays = overlays.dropLast(1) }
    val onTabSelected: (NavTab) -> Unit = { selected ->
        when (selected) {
            NavTab.SEARCH -> push(Overlay.Search)
            // Settings arrives with its own milestone; until then the pill has nothing to open.
            NavTab.SETTINGS -> Unit
            else -> tabFor(selected)?.let {
                tab = it
                rowReturn = null
            }
        }
    }
    // Details opens from any poster once the Details screen exists (Milestone 5).
    val openDetails: (MediaItem) -> Unit = {}
    val viewMore: (NavTab, CatalogRowSpec) -> Unit = { origin, row ->
        rowReturn = RowReturnTarget(origin, row.id)
        push(Overlay.ViewMore(row.title, row.source))
    }
    val returnFocusFor: (NavTab) -> ReturnRowFocus = { origin ->
        ReturnRowFocus(rowReturn.rowIdFor(origin)) { if (rowReturn?.tab == origin) rowReturn = null }
    }

    when (val top = overlays.lastOrNull()) {
        Overlay.Search -> {
            BackHandler(onBack = pop)
            SearchScreen(
                profileId = profileId,
                viewModel = activityViewModel(),
                onOpenDetails = openDetails,
                onProfileMissing = onSwitchProfile,
            )
        }
        is Overlay.ViewMore -> {
            BackHandler(onBack = pop)
            ViewMoreScreen(
                profileId = profileId,
                title = top.title,
                source = top.source,
                viewModel = activityViewModel(),
                onOpenDetails = openDetails,
                onProfileMissing = onSwitchProfile,
            )
        }
        null -> tabStates.SaveableStateProvider(tab.name) {
            when (tab) {
                NavTab.MOVIES -> MoviesScreen(
                    profileId, onTabSelected, onSwitchProfile, openDetails,
                    { viewMore(NavTab.MOVIES, it) }, returnFocusFor(NavTab.MOVIES),
                )
                NavTab.TV_SHOWS -> TvShowsScreen(
                    profileId, onTabSelected, onSwitchProfile, openDetails,
                    { viewMore(NavTab.TV_SHOWS, it) }, returnFocusFor(NavTab.TV_SHOWS),
                )
                NavTab.LIBRARY -> LibraryScreen(profileId, activityViewModel(), onTabSelected, onSwitchProfile, openDetails)
                else -> HomeScreen(
                    profileId = profileId,
                    viewModel = homeViewModel,
                    onNavigate = { destination ->
                        when (destination) {
                            is HomeDestination.ViewMore -> viewMore(NavTab.HOME, destination.row)
                            is HomeDestination.Details -> openDetails(destination.item)
                            // Playback arrives with the player (Milestone 6).
                            is HomeDestination.Play -> Unit
                        }
                    },
                    onTabSelected = onTabSelected,
                    onSwitchProfile = onSwitchProfile,
                    returnFocus = returnFocusFor(NavTab.HOME),
                )
            }
        }
    }
}
