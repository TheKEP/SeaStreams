package com.seastreams.tv.ui.screens.catalog

import androidx.compose.runtime.Composable
import com.seastreams.tv.core.datastore.CatalogRowScrollSpeed
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.ui.components.NavTab
import com.seastreams.tv.ui.components.ReturnRowFocus
import com.seastreams.tv.ui.navigation.activityViewModel

/** Movies tab (PRD §5): `Popular Movies`, the enabled streaming services, then the `Streaming services` toggles via `/discover/movie`. */
@Composable
fun MoviesScreen(
    profileId: Long,
    onTabSelected: (NavTab) -> Unit,
    onSwitchProfile: () -> Unit,
    onOpenDetails: (MediaItem) -> Unit,
    onViewMore: (CatalogRowSpec) -> Unit,
    returnFocus: ReturnRowFocus,
    scrollSpeed: CatalogRowScrollSpeed,
) {
    DiscoverTabScreen(
        profileId = profileId,
        mediaType = MediaType.MOVIE,
        tab = NavTab.MOVIES,
        viewModel = activityViewModel(key = "discover-movies"),
        onTabSelected = onTabSelected,
        onSwitchProfile = onSwitchProfile,
        onOpenDetails = onOpenDetails,
        onViewMore = onViewMore,
        returnFocus = returnFocus,
        scrollSpeed = scrollSpeed,
    )
}
