package com.seastreams.tv.ui.screens.profiles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.Text
import com.seastreams.tv.ui.theme.ErrorRed
import com.seastreams.tv.ui.theme.TextSecondary
import kotlinx.coroutines.launch

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
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Focus bring-into-view only reveals a button's unscaled bounds; scrolling to the very end also reveals the
    // bottom clearance, so the focused (scaled, ringed) Save / Delete pills are never cut flat by the viewport edge.
    val revealActions: () -> Unit = { scope.launch { scroll.animateScrollTo(scroll.maxValue) } }

    Row(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 48.dp, end = 48.dp, top = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scroll)
                // After verticalScroll, so the clearance is scrollable content below the last row.
                .padding(bottom = EditorBottomClearance),
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
            Actions(
                state,
                onSubmit = { viewModel.submit(onFinished) },
                onDelete = { viewModel.delete(onFinished) },
                onReveal = revealActions,
            )
        }
        PortalSetupPanel(
            state = state,
            onKeyInputChange = viewModel::onKeyInputChange,
            onVerifyKey = viewModel::verifyKey,
            modifier = Modifier.width(400.dp).padding(bottom = 32.dp).align(Alignment.CenterVertically),
        )
    }
}

@Composable
private fun Header(mode: ProfileEditorMode) {
    val (title, subtitle) = when (mode) {
        ProfileEditorMode.Onboarding ->
            "Welcome to seastreams" to "Create your first profile. A TMDB API key is required to continue."
        ProfileEditorMode.Create -> "New profile" to "Up to 5 profiles. Kids profiles only see age-appropriate titles."
        is ProfileEditorMode.Edit -> "Edit profile" to "Changes apply immediately."
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
}

@Composable
private fun Actions(state: ProfileCreateUiState, onSubmit: () -> Unit, onDelete: () -> Unit, onReveal: () -> Unit) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.onFocusChanged { if (it.hasFocus) onReveal() },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
                DeleteProfileButton(
                    armed = confirmDelete,
                    enabled = !state.form.saving,
                    onClick = {
                        if (confirmDelete) {
                            onDelete()
                        } else {
                            confirmDelete = true
                            onReveal()
                        }
                    },
                    onFocusLost = { confirmDelete = false },
                )
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

/**
 * Two-press delete. The pill never changes size: both labels are laid out on top of each other (the hidden one at
 * zero alpha), so it is always as wide as `Press again to delete`, and every state draws its border with the same
 * [CircleShape] as the container. A resize mid-press used to redraw the outline against a stale size and leave a
 * straight segment along the bottom edge. Leaving the button disarms it.
 */
@Composable
private fun DeleteProfileButton(armed: Boolean, enabled: Boolean, onClick: () -> Unit, onFocusLost: () -> Unit) {
    val stroke: (Float) -> Border = { alpha -> Border(BorderStroke(2.dp, ErrorRed.copy(alpha = alpha)), shape = CircleShape) }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.onFocusChanged { if (!it.hasFocus && armed) onFocusLost() },
        shape = OutlinedButtonDefaults.shape(CircleShape, CircleShape, CircleShape, CircleShape, CircleShape),
        border = OutlinedButtonDefaults.border(
            border = stroke(0.4f),
            focusedBorder = stroke(1f),
            pressedBorder = stroke(1f),
            disabledBorder = stroke(0.2f),
            focusedDisabledBorder = stroke(0.4f),
        ),
        colors = OutlinedButtonDefaults.colors(contentColor = ErrorRed, focusedContentColor = ErrorRed),
    ) {
        Box(contentAlignment = Alignment.Center) {
            DeleteLabel(DELETE_LABEL, visible = !armed)
            DeleteLabel(CONFIRM_DELETE_LABEL, visible = armed)
        }
    }
}

@Composable
private fun DeleteLabel(text: String, visible: Boolean) {
    Text(text, maxLines = 1, softWrap = false, modifier = Modifier.alpha(if (visible) 1f else 0f))
}

/**
 * Scrollable space below the editor's last row. The focused Save / Delete pills scale up (~1.1×) and draw a focus
 * ring past their layout bounds; with no room below them the scroll viewport clipped their bottom curve flat.
 */
internal val EditorBottomClearance = 32.dp

internal const val DELETE_LABEL = "Delete profile"
internal const val CONFIRM_DELETE_LABEL = "Press again to delete"
