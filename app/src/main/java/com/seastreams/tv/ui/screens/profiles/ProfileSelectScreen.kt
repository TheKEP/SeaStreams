package com.seastreams.tv.ui.screens.profiles

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.ui.components.AvatarTile
import com.seastreams.tv.ui.theme.FocusPill
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.focusPillColors
import com.seastreams.tv.ui.theme.tryRequestFocus
import com.seastreams.tv.ui.theme.verticalListEdgeWrap

/**
 * "Choose a Profile" (`docs/ui-references/profiles.png`): vertical profile column on the left with an
 * edit pencil beside the focused profile and a `+` tile (max 5 profiles), full-bleed artwork right.
 * Avatars size themselves ([profileGateMetrics]) so five profiles and `+` fit the 540 dp viewport;
 * `Up`/`Down` follow the two-way edge-stop-then-wrap rule between the first profile and the last row.
 */
@Composable
fun ProfileSelectScreen(
    viewModel: ProfileSelectViewModel,
    onProfileSelected: (Long) -> Unit,
    onEditProfile: (Long) -> Unit,
    onAddProfile: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val initialFocus = remember { FocusRequester() }
    val initialIndex = state.profiles.indexOfFirst { it.id == state.activeProfileId }.coerceAtLeast(0)
    LaunchedEffect(state.profiles.isNotEmpty()) {
        if (state.profiles.isNotEmpty()) runCatching { initialFocus.requestFocus() }
    }

    // Rows: every profile, then `+` while under the cap. Two-way edge-stop-then-wrap between the first and last row.
    val slotCount = state.profiles.size + if (state.canAddProfile) 1 else 0
    val lastIndex = slotCount - 1
    val firstSlot = remember { FocusRequester() }
    val lastSlot = remember { FocusRequester() }
    val edge: (Int) -> Modifier = { index ->
        Modifier.verticalListEdgeWrap(index, lastIndex, toFirst = { firstSlot.tryRequestFocus() }, toLast = { lastSlot.tryRequestFocus() })
    }
    val slotRequesters: (Int) -> Modifier = { index ->
        Modifier
            .then(if (index == 0) Modifier.focusRequester(firstSlot) else Modifier)
            .then(if (index == lastIndex) Modifier.focusRequester(lastSlot) else Modifier)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ProfileGateBackdrop(state.backdropUrl, Modifier.align(Alignment.CenterEnd))
        Column(Modifier.fillMaxHeight().padding(start = 40.dp, top = 28.dp, bottom = 16.dp)) {
            Text(
                "Choose a Profile",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 56.dp, bottom = 12.dp),
            )
            // All five profiles and `+` must fit the 540 dp TV viewport: the rows size themselves to what is left.
            BoxWithConstraints(Modifier.weight(1f)) {
                val metrics = profileGateMetrics(slotCount, maxHeight)
                Column(verticalArrangement = Arrangement.spacedBy(metrics.spacing)) {
                    state.profiles.forEachIndexed { index, profile ->
                        ProfileRow(
                            profile = profile,
                            isActive = profile.id == state.activeProfileId,
                            metrics = metrics,
                            rowModifier = edge(index),
                            tileModifier = slotRequesters(index)
                                .then(if (index == initialIndex) Modifier.focusRequester(initialFocus) else Modifier),
                            onSelect = {
                                viewModel.selectProfile(profile.id) { onProfileSelected(profile.id) }
                            },
                            onEdit = { onEditProfile(profile.id) },
                            onFocused = { viewModel.prefetchHome(profile) },
                        )
                    }
                    if (state.canAddProfile) {
                        val index = state.profiles.size
                        AddProfileTile(metrics, edge(index), slotRequesters(index), onAddProfile)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    profile: ProfileEntity,
    isActive: Boolean,
    metrics: ProfileGateMetrics,
    rowModifier: Modifier,
    tileModifier: Modifier,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onFocused: () -> Unit,
) {
    var rowHasFocus by remember { mutableStateOf(false) }
    var tileFocused by remember { mutableStateOf(false) }
    val tileSize by animateDpAsState(if (tileFocused) metrics.focusedTile else metrics.tile, label = "profileTileSize")

    Row(
        rowModifier.onFocusChanged { rowHasFocus = it.hasFocus },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pencil stays focusable (Left from the tile) but is only drawn for the focused row.
        Surface(
            onClick = onEdit,
            modifier = Modifier.size(40.dp).alpha(if (rowHasFocus) 1f else 0f),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = focusPillColors(contentColor = TextSecondary),
        ) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit ${profile.name}", modifier = Modifier.padding(10.dp))
        }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.size(metrics.slot), contentAlignment = Alignment.Center) {
            ProfileTileSurface(
                size = tileSize,
                highlighted = isActive,
                onClick = onSelect,
                modifier = tileModifier.onFocusChanged {
                    if (it.isFocused && !tileFocused) onFocused()
                    tileFocused = it.isFocused
                },
            ) {
                AvatarTile(avatarIndex = profile.avatarIndex, size = tileSize)
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.alpha(if (rowHasFocus) 1f else 0f)) {
            Text(profile.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (profile.isKids) {
                Text(
                    "Kids · ${profile.movieCeiling?.label.orEmpty()} / ${profile.tvCeiling?.label.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun AddProfileTile(metrics: ProfileGateMetrics, rowModifier: Modifier, tileModifier: Modifier, onClick: () -> Unit) {
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(56.dp))
        Box(Modifier.size(metrics.slot), contentAlignment = Alignment.Center) {
            ProfileTileSurface(size = metrics.tile, highlighted = false, onClick = onClick, modifier = tileModifier) {
                Box(
                    Modifier.size(metrics.tile).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add profile", modifier = Modifier.size(metrics.tile * 0.42f))
                }
            }
        }
    }
}

@Composable
private fun ProfileTileSurface(
    size: Dp,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(size * 0.16f)
    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = PROFILE_TILE_FOCUS_SCALE),
        border = ClickableSurfaceDefaults.border(
            border = if (highlighted) Border(BorderStroke(2.dp, FocusPill.copy(alpha = 0.5f)), shape = shape) else Border.None,
            focusedBorder = Border(BorderStroke(4.dp, FocusPill), shape = shape),
        ),
    ) { content() }
}
