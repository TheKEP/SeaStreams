package com.seastreams.tv.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import com.seastreams.tv.ui.screens.home.HomeRevealGate
import com.seastreams.tv.ui.screens.home.HomeViewModel
import com.seastreams.tv.ui.screens.profiles.ProfileCreateScreen
import com.seastreams.tv.ui.screens.profiles.ProfileEditorMode
import com.seastreams.tv.ui.screens.profiles.ProfileSelectViewModel
import com.seastreams.tv.ui.screens.splash.SeaStreamsSplashScreen

@Composable
fun SeaStreamsNavGraph(backLongPress: BackLongPressRouter) {
    val gateViewModel: AppGateViewModel = activityViewModel()
    val gate by gateViewModel.gate.collectAsState()
    // Saveable so the splash shows once per process, not again after activity recreation.
    var splashDone by rememberSaveable { mutableStateOf(false) }

    if (!splashDone) {
        // Creating the (activity-scoped) gate VM now starts its backdrop fetch + decode behind the
        // splash. Only once profiles exist: the Kids-aware artwork filter (rule 6) reads the
        // profile list when the fetch starts.
        if (gate == AppGate.Ready) activityViewModel<ProfileSelectViewModel>()
        SeaStreamsSplashScreen(
            isStartupReady = gate != AppGate.Loading,
            onSplashFinished = { splashDone = true },
        )
        return
    }

    when (gate) {
        AppGate.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        AppGate.NeedsOnboarding -> ProfileCreateScreen(
            mode = ProfileEditorMode.Onboarding,
            viewModel = activityViewModel(key = "profile-onboarding"),
            // The gate flips to Ready on its own once the profile row and key both exist.
            onFinished = {},
        )
        AppGate.Ready -> ProfileGate(backLongPress)
    }
}

/**
 * Profile switcher, and once a profile is chosen, that profile's tabs (starting on Home). The shell
 * is keyed on ([homeProfileId], `shellSession`): a pick from the `Settings → Profiles` cover bumps the
 * session, so even re-picking the current profile starts a fresh shell on Home with Settings closed.
 */
@Composable
private fun ProfileGate(backLongPress: BackLongPressRouter) {
    var homeProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var shellSession by rememberSaveable { mutableIntStateOf(0) }
    val openProfileId = homeProfileId
    if (openProfileId != null) {
        val homeViewModel: HomeViewModel = activityViewModel()
        key(openProfileId, shellSession) {
            HomeRevealGate(profileId = openProfileId, viewModel = homeViewModel, onBack = { homeProfileId = null }) {
                ProfileShell(
                    profileId = openProfileId,
                    homeViewModel = homeViewModel,
                    backLongPress = backLongPress,
                    onSwitchProfile = { homeProfileId = null },
                    onEnterProfile = { id ->
                        homeProfileId = id
                        shellSession++
                    },
                )
            }
        }
        return
    }
    ProfileSwitcher(onProfileSelected = { homeProfileId = it })
}
