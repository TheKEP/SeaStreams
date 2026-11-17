package com.seastreams.tv.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.ui.components.CoverableLayer
import com.seastreams.tv.ui.components.FocusOriginTracker
import com.seastreams.tv.ui.components.LocalFocusOrigins
import com.seastreams.tv.ui.components.LocalTransientMessages
import com.seastreams.tv.ui.components.LocalWatchedTitles
import com.seastreams.tv.ui.components.NavTab
import com.seastreams.tv.ui.components.ReturnRowFocus
import com.seastreams.tv.ui.components.RowReturnTarget
import com.seastreams.tv.ui.components.TransientMessageHost
import com.seastreams.tv.ui.components.TransientMessages
import com.seastreams.tv.ui.components.rowIdFor
import com.seastreams.tv.ui.screens.catalog.MoviesScreen
import com.seastreams.tv.ui.screens.catalog.TvShowsScreen
import com.seastreams.tv.ui.screens.catalog.ViewMoreScreen
import com.seastreams.tv.ui.screens.home.HomeDestination
import com.seastreams.tv.ui.screens.home.HomeScreen
import com.seastreams.tv.ui.screens.home.HomeViewModel
import com.seastreams.tv.ui.screens.library.LibraryScreen
import com.seastreams.tv.ui.screens.playback.PlaybackHost
import com.seastreams.tv.ui.screens.playback.PlaybackViewModel
import com.seastreams.tv.ui.screens.playback.RESTORE_FRAMES
import com.seastreams.tv.ui.screens.search.SearchScreen
import com.seastreams.tv.ui.theme.AppearanceViewModel
import com.seastreams.tv.ui.theme.retryEachFrame
import com.seastreams.tv.ui.theme.tryRequestFocus
import kotlinx.coroutines.launch

/** Full-screen pages opened on top of the current tab, replacing it; `Back` closes the top one. */
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
 * Everything after a profile is chosen: the Home / Movies / TV Shows / Library tabs, Search and View
 * More pages stacked over them, and on top of whichever page is showing the [Cover] layers (Details,
 * Episodes, Settings).
 *
 * Covers keep the page beneath composed (only hidden and unfocusable), so on `Back` focus goes
 * straight back to the exact card or button that opened them (PRD §6 Exact Focus Restoration).
 */
@Composable
fun ProfileShell(profileId: Long, homeViewModel: HomeViewModel, onSwitchProfile: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(NavTab.HOME) }
    var overlays by remember { mutableStateOf(emptyList<Overlay>()) }
    var covers by remember { mutableStateOf(emptyList<CoverEntry>()) }
    var nextCoverId by remember { mutableLongStateOf(0L) }
    val tabStates = rememberSaveableStateHolder()
    // The row whose View More is open, kept while that page is popped so its first card regains focus.
    var rowReturn by remember { mutableStateOf<RowReturnTarget?>(null) }
    val origins = remember { FocusOriginTracker() }
    val messages = remember { TransientMessages() }
    val playback: PlaybackViewModel = activityViewModel()
    val playbackState by playback.state.collectAsState()
    val scope = rememberCoroutineScope()
    val watchedTitles: WatchedTitlesViewModel = activityViewModel()
    LaunchedEffect(profileId) { watchedTitles.open(profileId) }
    val watched by watchedTitles.watched.collectAsState()
    val appearance: AppearanceViewModel = activityViewModel()
    val rowScrollSpeed by appearance.catalogRowScrollSpeed.collectAsState()

    val push: (Overlay) -> Unit = { overlays = overlays + it }
    val pop: () -> Unit = { overlays = overlays.dropLast(1) }
    val pushCover: (Cover) -> Unit = { cover ->
        covers = covers + CoverEntry(cover, origins.take(), nextCoverId++)
    }
    val popCover: () -> Unit = {
        covers.lastOrNull()?.let { top ->
            covers = covers.dropLast(1)
            top.origin?.let { origin -> scope.launch { retryEachFrame(RESTORE_FRAMES) { origin.tryRequestFocus() } } }
        }
    }
    val updateCover: (Long, Cover) -> Unit = { id, cover ->
        covers = covers.map { if (it.id == id) it.copy(cover = cover) else it }
    }
    val onTabSelected: (NavTab) -> Unit = { selected ->
        when (selected) {
            NavTab.SEARCH -> push(Overlay.Search)
            NavTab.SETTINGS -> pushCover(Cover.Settings)
            else -> tabFor(selected)?.let {
                tab = it
                rowReturn = null
            }
        }
    }
    val openDetails: (MediaItem) -> Unit = { pushCover(Cover.Details(it, autoPlay = false)) }
    val viewMore: (NavTab, CatalogRowSpec) -> Unit = { origin, row ->
        rowReturn = RowReturnTarget(origin, row.id)
        push(Overlay.ViewMore(row.title, row.source))
    }
    val returnFocusFor: (NavTab) -> ReturnRowFocus = { origin ->
        ReturnRowFocus(rowReturn.rowIdFor(origin)) { if (rowReturn?.tab == origin) rowReturn = null }
    }

    CompositionLocalProvider(
        LocalFocusOrigins provides origins,
        LocalTransientMessages provides messages,
        LocalWatchedTitles provides watched,
    ) {
        Box(Modifier.fillMaxSize()) {
            val pickerOpen = playbackState.picker != null
            CoverableLayer(covered = covers.isNotEmpty() || pickerOpen, trapFocus = false, visible = covers.isEmpty()) {
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
                                { viewMore(NavTab.MOVIES, it) }, returnFocusFor(NavTab.MOVIES), rowScrollSpeed,
                            )
                            NavTab.TV_SHOWS -> TvShowsScreen(
                                profileId, onTabSelected, onSwitchProfile, openDetails,
                                { viewMore(NavTab.TV_SHOWS, it) }, returnFocusFor(NavTab.TV_SHOWS), rowScrollSpeed,
                            )
                            NavTab.LIBRARY -> LibraryScreen(profileId, activityViewModel(), onTabSelected, onSwitchProfile, openDetails)
                            else -> HomeScreen(
                                profileId = profileId,
                                viewModel = homeViewModel,
                                onNavigate = { destination ->
                                    when (destination) {
                                        is HomeDestination.ViewMore -> viewMore(NavTab.HOME, destination.row)
                                        is HomeDestination.Details -> openDetails(destination.item)
                                        // Auto-play from Continue Watching: Details opens and starts playing.
                                        is HomeDestination.Play -> pushCover(Cover.Details(destination.item.media, autoPlay = true))
                                    }
                                },
                                onTabSelected = onTabSelected,
                                onSwitchProfile = onSwitchProfile,
                                returnFocus = returnFocusFor(NavTab.HOME),
                                scrollSpeed = rowScrollSpeed,
                            )
                        }
                    }
                }
            }
            covers.forEachIndexed { index, entry ->
                key(entry.id) {
                    val covered = index < covers.lastIndex || pickerOpen
                    CoverableLayer(covered = covered, trapFocus = true, visible = index == covers.lastIndex) {
                        BackHandler(enabled = !covered, onBack = popCover)
                        CoverContent(
                            entry = entry,
                            profileId = profileId,
                            playback = playback,
                            onPush = pushCover,
                            onUpdate = updateCover,
                            onClose = popCover,
                        )
                    }
                }
            }
            PlaybackHost(playback, messages, origins)
            TransientMessageHost(messages)
        }
    }
}
