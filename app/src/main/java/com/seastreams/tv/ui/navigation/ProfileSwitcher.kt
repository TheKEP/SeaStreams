package com.seastreams.tv.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.seastreams.tv.ui.screens.profiles.ProfileCreateScreen
import com.seastreams.tv.ui.screens.profiles.ProfileEditorMode
import com.seastreams.tv.ui.screens.profiles.ProfileSelectScreen

/** Editor overlay state: closed, creating a new profile, or editing the profile with that id. */
private const val EDITOR_CLOSED = -1L
private const val EDITOR_NEW = 0L

/**
 * The profile switcher (`profiles.jpg`): `ProfileSelectScreen`, with its edit pencil / `+` opening
 * `ProfileCreateScreen` on top (`Back` closes the editor). Shared by the startup Profile Gate and
 * the `Settings → Home → Profiles` cover (PRD §2), so both behave the same.
 */
@Composable
fun ProfileSwitcher(onProfileSelected: (Long) -> Unit) {
    var editor by rememberSaveable { mutableLongStateOf(EDITOR_CLOSED) }
    if (editor == EDITOR_CLOSED) {
        ProfileSelectScreen(
            viewModel = activityViewModel(),
            onProfileSelected = onProfileSelected,
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
