package com.seastreams.tv.ui.screens.profiles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.seastreams.tv.data.profile.KidsCeilings
import com.seastreams.tv.data.profile.ProfileDraft
import com.seastreams.tv.domain.model.MovieCertification
import com.seastreams.tv.domain.model.ProfileAvatar
import com.seastreams.tv.domain.model.TvCertification
import com.seastreams.tv.ui.components.AvatarTile
import com.seastreams.tv.ui.components.TvTextField
import com.seastreams.tv.ui.theme.FocusPill
import com.seastreams.tv.ui.theme.TextSecondary

private const val AVATARS_PER_ROW = 4

/** Callbacks for [ProfileForm]; grouped to keep the composable signature readable. */
data class ProfileFormActions(
    val onNameChange: (String) -> Unit,
    val onAvatarSelected: (Int) -> Unit,
    val onKidsChange: (Boolean) -> Unit,
    val onMovieCeiling: (MovieCertification) -> Unit,
    val onTvCeiling: (TvCertification) -> Unit,
    val onAllowUnratedChange: (Boolean) -> Unit,
)

/** Name, avatar and Kids age-restriction fields (PRD §2). */
@Composable
fun ProfileForm(draft: ProfileDraft, actions: ProfileFormActions, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        TvTextField(
            value = draft.name,
            onValueChange = actions.onNameChange,
            label = "Profile name",
            placeholder = "e.g. John",
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Avatar")
            ProfileAvatar.entries.indices.chunked(AVATARS_PER_ROW).forEach { rowIndices ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowIndices.forEach { index ->
                        AvatarChoice(index = index, selected = index == draft.avatarIndex, onClick = { actions.onAvatarSelected(index) })
                    }
                }
            }
        }

        ToggleRow(
            title = "Kids profile",
            subtitle = "Hides titles above the chosen ratings everywhere in the app",
            checked = draft.isKids,
            onCheckedChange = actions.onKidsChange,
        )

        if (draft.isKids) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Highest movie rating")
                ChipRow(KidsCeilings.MOVIE, draft.movieCeiling, { it.label }, actions.onMovieCeiling)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Highest TV rating")
                ChipRow(KidsCeilings.TV, draft.tvCeiling, { it.label }, actions.onTvCeiling)
            }
            ToggleRow(
                title = "Allow unrated titles",
                subtitle = "Titles with no US rating are hidden when this is off",
                checked = draft.allowUnrated,
                onCheckedChange = actions.onAllowUnratedChange,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = TextSecondary)
}

@Composable
private fun AvatarChoice(index: Int, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(3.dp, FocusPill.copy(alpha = 0.6f)), shape = shape) else Border.None,
            focusedBorder = Border(BorderStroke(3.dp, FocusPill), shape = shape),
        ),
    ) {
        AvatarTile(avatarIndex = index, size = 52.dp)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }) { Text(label(option)) }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Spacer(Modifier.width(16.dp))
            // Display only: the whole row is the focus/click target, so the switch never takes focus.
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}
