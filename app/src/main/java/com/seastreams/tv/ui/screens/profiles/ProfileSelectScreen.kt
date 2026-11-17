package com.seastreams.tv.ui.screens.profiles

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.ui.components.AvatarTile
import com.seastreams.tv.ui.theme.FocusPill
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.focusPillColors

private val TileSize = 76.dp
private val FocusedTileSize = 108.dp

/**
 * "Choose a Profile" (`docs/ui-references/profiles.png`): vertical profile column on the left with an
 * edit pencil beside the focused profile and a `+` tile (max 5 profiles), full-bleed artwork right.
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

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Backdrop(state.backdropUrl, Modifier.align(Alignment.CenterEnd))
        Column(
            Modifier.fillMaxHeight().padding(start = 40.dp, top = 40.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "Choose a Profile",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 56.dp),
            )
            state.profiles.forEachIndexed { index, profile ->
                ProfileRow(
                    profile = profile,
                    isActive = profile.id == state.activeProfileId,
                    tileModifier = if (index == initialIndex) Modifier.focusRequester(initialFocus) else Modifier,
                    onSelect = {
                        viewModel.selectProfile(profile.id)
                        onProfileSelected(profile.id)
                    },
                    onEdit = { onEditProfile(profile.id) },
                    onFocused = { viewModel.prefetchHome(profile) },
                )
            }
            if (state.canAddProfile) AddProfileTile(onAddProfile)
        }
    }
}

@Composable
private fun Backdrop(url: String?, modifier: Modifier) {
    Box(modifier.fillMaxHeight().fillMaxWidth(0.76f)) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Left vignette blending the artwork into the profile column.
        Box(
            Modifier.fillMaxHeight().width(360.dp)
                .background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background.copy(alpha = 0.6f), Color.Transparent))),
        )
    }
}

@Composable
private fun ProfileRow(
    profile: ProfileEntity,
    isActive: Boolean,
    tileModifier: Modifier,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onFocused: () -> Unit,
) {
    var rowHasFocus by remember { mutableStateOf(false) }
    var tileFocused by remember { mutableStateOf(false) }
    val tileSize by animateDpAsState(if (tileFocused) FocusedTileSize else TileSize, label = "profileTileSize")

    Row(
        Modifier.onFocusChanged { rowHasFocus = it.hasFocus },
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
        Box(Modifier.size(FocusedTileSize), contentAlignment = Alignment.Center) {
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
private fun AddProfileTile(onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(56.dp))
        Box(Modifier.size(FocusedTileSize), contentAlignment = Alignment.Center) {
            ProfileTileSurface(size = TileSize, highlighted = false, onClick = onClick) {
                Box(
                    Modifier.size(TileSize).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add profile", modifier = Modifier.size(32.dp))
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
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = if (highlighted) Border(BorderStroke(2.dp, FocusPill.copy(alpha = 0.5f)), shape = shape) else Border.None,
            focusedBorder = Border(BorderStroke(4.dp, FocusPill), shape = shape),
        ),
    ) { content() }
}
