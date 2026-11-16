package com.seastreams.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.ui.theme.FocusPill
import com.seastreams.tv.ui.theme.OnFocusPill
import com.seastreams.tv.ui.theme.TextPrimary
import com.seastreams.tv.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Top navigation pills (PRD §1). Search and Settings render as icons. */
enum class NavTab(val label: String, val icon: ImageVector? = null) {
    SEARCH("Search", Icons.Filled.Search),
    HOME("Home"),
    MOVIES("Movies"),
    TV_SHOWS("TV Shows"),
    LIBRARY("Library"),
    SETTINGS("Settings", Icons.Filled.Settings),
    ;

    companion object {
        /** Kids profiles never see the Movies and TV Shows tabs. */
        fun visibleFor(isKids: Boolean): List<NavTab> =
            if (isKids) entries.filterNot { it == MOVIES || it == TV_SHOWS } else entries
    }
}

/**
 * Top-left: profile avatar (opens the profile switcher) and a live 12-hour `h:mm a` clock.
 * Top-right: translucent pill bar with the tabs for this profile.
 *
 * The bar is one focus group: entering it from below restores the last-focused pill, defaulting to
 * [homeFocusRequester] (the Home pill).
 */
@Composable
fun TopNavBar(
    profile: ProfileEntity?,
    selectedTab: NavTab,
    homeFocusRequester: FocusRequester,
    onTabSelected: (NavTab) -> Unit,
    onAvatarClick: () -> Unit,
    onHomeFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = RowGutter, vertical = 16.dp)
            .focusRestorer(homeFocusRequester)
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onAvatarClick,
            modifier = Modifier.size(44.dp),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, FocusPill), shape = CircleShape),
            ),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        ) {
            AvatarTile(avatarIndex = profile?.avatarIndex ?: 0, size = 44.dp)
        }
        Spacer(Modifier.size(16.dp))
        LiveClock()
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Until the profile is known, show only the tabs every profile type gets.
            NavTab.visibleFor(isKids = profile?.isKids ?: true).forEach { tab ->
                NavPill(
                    tab = tab,
                    selected = tab == selectedTab,
                    onClick = { onTabSelected(tab) },
                    modifier = if (tab == NavTab.HOME) {
                        Modifier.focusRequester(homeFocusRequester).onFocusChanged { onHomeFocusChanged(it.isFocused) }
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

@Composable
private fun NavPill(tab: NavTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent,
            contentColor = if (selected) TextPrimary else TextSecondary,
            focusedContainerColor = FocusPill,
            focusedContentColor = OnFocusPill,
        ),
    ) {
        val icon = tab.icon
        if (icon != null) {
            Icon(icon, contentDescription = tab.label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).size(20.dp))
        } else {
            Text(
                tab.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** System time as 12-hour `h:mm a` (e.g. `12:15 AM`), re-read at each minute boundary. */
@Composable
private fun LiveClock() {
    val time by produceState(initialValue = LocalTime.now()) {
        while (true) {
            val now = LocalTime.now()
            delay((SECONDS_PER_MINUTE - now.second) * MILLIS_PER_SECOND - now.nano / NANOS_PER_MILLI)
            value = LocalTime.now()
        }
    }
    Text(formatClockTime(time), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
}

/** 12-hour clock text, e.g. `12:15 AM`. Locale.US keeps the `AM`/`PM` markers fixed across device locales. */
internal fun formatClockTime(time: LocalTime): String = time.format(ClockFormat)

private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private const val SECONDS_PER_MINUTE = 60L
private const val MILLIS_PER_SECOND = 1_000L
private const val NANOS_PER_MILLI = 1_000_000L
