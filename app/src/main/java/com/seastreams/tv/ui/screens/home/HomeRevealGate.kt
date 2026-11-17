package com.seastreams.tv.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.ui.components.AvatarTile
import com.seastreams.tv.ui.theme.TextSecondary

/**
 * Profile → Home transition. Holds a minimal badge screen while [profileId]'s hero, first row and
 * their images load (at most [REVEAL_TIMEOUT_MILLIS]), then crossfades into [content] already
 * populated. `Back` while waiting returns to the profile switcher via [onBack].
 */
@Composable
fun HomeRevealGate(
    profileId: Long,
    viewModel: HomeViewModel,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    var revealed by rememberSaveable(profileId) { mutableStateOf(false) }
    LaunchedEffect(profileId) {
        if (!revealed) {
            viewModel.awaitFirstScreen(profileId)
            revealed = true
        }
    }
    Crossfade(
        targetState = revealed,
        animationSpec = tween(REVEAL_CROSSFADE_MILLIS),
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        label = "homeReveal",
    ) { shown ->
        if (shown) {
            content()
        } else {
            BackHandler(onBack = onBack)
            val latest by viewModel.uiState.collectAsState()
            ProfileLoadingBadge(latest.profile?.takeIf { it.id == profileId })
        }
    }
}

/** Dark screen with the chosen profile's avatar gently pulsing; nothing on it is focusable. */
@Composable
private fun ProfileLoadingBadge(profile: ProfileEntity?) {
    val pulse by rememberInfiniteTransition(label = "profileBadge").animateFloat(
        initialValue = PULSE_MIN_ALPHA,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
        label = "profileBadgeAlpha",
    )
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        if (profile != null) {
            Column(
                Modifier.graphicsLayer { alpha = pulse },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AvatarTile(avatarIndex = profile.avatarIndex, size = 96.dp)
                Text(profile.name, style = MaterialTheme.typography.titleMedium, color = TextSecondary)
            }
        }
    }
}

private const val PULSE_MIN_ALPHA = 0.55f
private const val PULSE_MILLIS = 700
