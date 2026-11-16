package com.seastreams.tv.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import com.seastreams.tv.ui.screens.home.HomeRevealGate
import com.seastreams.tv.ui.screens.home.HomeViewModel
import com.seastreams.tv.ui.screens.profiles.ProfileCreateScreen
import com.seastreams.tv.ui.screens.profiles.ProfileEditorMode
import com.seastreams.tv.ui.screens.profiles.ProfileSelectScreen

/** Editor overlay state: closed, creating a new profile, or editing the profile with that id. */
private const val EDITOR_CLOSED = -1L
private const val EDITOR_NEW = 0L

@Composable
fun SeaStreamsNavGraph() {
    val gateViewModel: AppGateViewModel = activityViewModel()
    val gate by gateViewModel.gate.collectAsState()

    when (gate) {
        AppGate.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        AppGate.NeedsOnboarding -> ProfileCreateScreen(
            mode = ProfileEditorMode.Onboarding,
            viewModel = activityViewModel(key = "profile-onboarding"),
            // The gate flips to Ready on its own once the profile row and key both exist.
            onFinished = {},
        )
        AppGate.Ready -> ProfileGate()
    }
}

/** Profile switcher, and once a profile is chosen, that profile's tabs (starting on Home). */
@Composable
private fun ProfileGate() {
    var homeProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    val openProfileId = homeProfileId
    if (openProfileId != null) {
        val homeViewModel: HomeViewModel = activityViewModel()
        HomeRevealGate(profileId = openProfileId, viewModel = homeViewModel, onBack = { homeProfileId = null }) {
            ProfileShell(profileId = openProfileId, homeViewModel = homeViewModel, onSwitchProfile = { homeProfileId = null })
        }
        return
    }
    var editor by rememberSaveable { mutableLongStateOf(EDITOR_CLOSED) }
    if (editor == EDITOR_CLOSED) {
        ProfileSelectScreen(
            viewModel = activityViewModel(),
            onProfileSelected = { homeProfileId = it },
            onEditProfile = { editor = it },
            onAddProfile = { editor = EDITOR_NEW },
        )
    } else {
        BackHandler { editor = EDITOR_CLOSED }
        ProfileCreateScreen(
            mode = if (editor == EDITOR_NEW) ProfileEditorMode.Create else ProfileEditorMode.Edit(editor),
            viewModel = activityViewModel(key = "profile-editor"),
            onFinished = { editor = EDITOR_CLOSED },
        )
    }
}
