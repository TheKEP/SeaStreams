package com.seastreams.tv.ui.screens.catalog

import androidx.compose.runtime.Composable
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.ui.components.NavTab
import com.seastreams.tv.ui.components.ReturnRowFocus
import com.seastreams.tv.ui.navigation.activityViewModel

/** TV Shows tab (PRD §5): `Popular Shows`, the enabled streaming services, then the `Streaming services` toggles via `/discover/tv`. */
@Composable
fun TvShowsScreen(
    profileId: Long,
    onTabSelected: (NavTab) -> Unit,
    onSwitchProfile: () -> Unit,
    onOpenDetails: (MediaItem) -> Unit,
    onViewMore: (CatalogRowSpec) -> Unit,
    returnFocus: ReturnRowFocus,
) {
    DiscoverTabScreen(
        profileId = profileId,
        mediaType = MediaType.TV,
        tab = NavTab.TV_SHOWS,
        viewModel = activityViewModel(key = "discover-tv"),
        onTabSelected = onTabSelected,
        onSwitchProfile = onSwitchProfile,
        onOpenDetails = onOpenDetails,
        onViewMore = onViewMore,
        returnFocus = returnFocus,
    )
}
