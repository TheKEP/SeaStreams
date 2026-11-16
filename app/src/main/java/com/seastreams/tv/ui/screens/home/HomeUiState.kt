package com.seastreams.tv.ui.screens.home

import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.domain.model.CatalogRowState
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.MediaItem

sealed interface HeroState {
    data object Loading : HeroState
    data class Loaded(val items: List<MediaItem>) : HeroState
    data object Failed : HeroState
}

data class HomeUiState(
    /** Profile the content below belongs to; `null` while it is being read. */
    val profile: ProfileEntity? = null,
    /** Set when this profile was deleted while Home was open (e.g. from the Web Portal). */
    val missingProfileId: Long? = null,
    val hero: HeroState = HeroState.Loading,
    val continueWatching: List<ContinueWatchingItem> = emptyList(),
    val rows: List<CatalogRowState> = emptyList(),
    val autoPlayFromContinueWatching: Boolean = false,
)

/** Where a click on Home leads; the nav graph maps these to screens. */
sealed interface HomeDestination {
    data class Details(val item: MediaItem) : HomeDestination
    data class Play(val item: ContinueWatchingItem) : HomeDestination
    data class ViewMore(val rowId: String, val title: String, val listUrl: String) : HomeDestination
}

/** PRD §3: Continue Watching plays immediately when auto-play is on, else opens Details. */
fun continueWatchingDestination(item: ContinueWatchingItem, autoPlay: Boolean): HomeDestination =
    if (autoPlay) HomeDestination.Play(item) else HomeDestination.Details(item.media)
