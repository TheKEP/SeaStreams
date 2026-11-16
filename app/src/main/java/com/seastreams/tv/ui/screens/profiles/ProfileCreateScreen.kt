package com.seastreams.tv.ui.screens.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.Text
import com.seastreams.tv.ui.theme.ErrorRed
import com.seastreams.tv.ui.theme.TextSecondary

/**
 * Profile creation/editing. In [ProfileEditorMode.Onboarding] (no profiles yet) the Continue button
 * stays disabled until a TMDB-validated key exists; because the key state is observed, saving it
 * from the Web Portal on a phone unlocks the button on the TV without any interaction.
 */
@Composable
fun ProfileCreateScreen(
    mode: ProfileEditorMode,
    viewModel: ProfileCreateViewModel,
    onFinished: (profileId: Long) -> Unit,
) {
    LaunchedEffect(mode) { viewModel.start(mode) }
    val state by viewModel.uiState.collectAsState()
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }

    Row(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Header(mode)
            ProfileForm(
                draft = state.form.draft,
                actions = ProfileFormActions(
                    onNameChange = viewModel::onNameChange,
                    onAvatarSelected = viewModel::onAvatarSelected,
                    onKidsChange = viewModel::onKidsChange,
                    onMovieCeiling = viewModel::onMovieCeiling,
                    onTvCeiling = viewModel::onTvCeiling,
                    onAllowUnratedChange = viewModel::onAllowUnratedChange,
                ),
                modifier = Modifier.focusRequester(firstField),
            )
            state.form.error?.let { Text(it, color = ErrorRed, style = MaterialTheme.typography.bodyMedium) }
            Actions(state, onSubmit = { viewModel.submit(onFinished) }, onDelete = { viewModel.delete(onFinished) })
        }
        PortalSetupPanel(
            state = state,
            onKeyInputChange = viewModel::onKeyInputChange,
            onVerifyKey = viewModel::verifyKey,
            modifier = Modifier.width(400.dp).align(Alignment.CenterVertically),
        )
    }
}

@Composable
private fun Header(mode: ProfileEditorMode) {
    val (title, subtitle) = when (mode) {
        ProfileEditorMode.Onboarding ->
            "Welcome to SeaStreams" to "Create your first profile. A TMDB API key is required to continue."
        ProfileEditorMode.Create -> "New profile" to "Up to 5 profiles. Kids profiles only see age-appropriate titles."
        is ProfileEditorMode.Edit -> "Edit profile" to "Changes apply immediately."
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
}

@Composable
private fun Actions(state: ProfileCreateUiState, onSubmit: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onSubmit, enabled = state.canSubmit) {
                Text(
                    when {
                        state.form.saving -> "Saving…"
                        state.mode is ProfileEditorMode.Edit -> "Save"
                        else -> "Continue"
                    },
                )
            }
            if (state.mode is ProfileEditorMode.Edit) {
                OutlinedButton(
                    onClick = { if (confirmDelete) onDelete() else confirmDelete = true },
                    enabled = !state.form.saving,
                    colors = OutlinedButtonDefaults.colors(contentColor = ErrorRed, focusedContentColor = ErrorRed),
                ) {
                    Text(if (confirmDelete) "Press again to delete" else "Delete profile")
                }
            }
        }
        if (!state.keySaved) {
            Text(
                "Continue unlocks once a valid TMDB key is saved here or from your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        } else if (state.form.draft.name.isBlank()) {
            Text("Enter a profile name to continue.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}
