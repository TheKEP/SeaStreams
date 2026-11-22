package com.seastreams.tv.ui.screens.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.ui.theme.FocusPill
import com.seastreams.tv.ui.theme.OutlineDim
import com.seastreams.tv.ui.theme.SuccessGreen
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.focusPillColors
import com.seastreams.tv.ui.theme.onDpadKeyDown

/** What sits at the right end of a settings row (`settings-example.jpg`). */
sealed interface RowTrailing {
    /** Opens a sub-menu or picker. */
    data object Chevron : RowTrailing

    data class Toggle(val checked: Boolean) : RowTrailing

    /** `‹ value ›`: `Left`/`Right` step the value while the row is focused. */
    data class Stepper(val onDecrease: () -> Unit, val onIncrease: () -> Unit) : RowTrailing

    data object None : RowTrailing
}

/**
 * A wide pill row: icon, title, muted current value, and a chevron / switch / stepper. Rows are
 * translucent at rest ([colors]) and invert to the light [FocusPill] with dark content while focused.
 * An [indented] row is a conditional sub-setting: it starts further right, behind a thin vertical
 * connector that ties it to the parent row above (PRD §11).
 */
@Composable
fun SettingsRow(
    icon: ImageVector?,
    title: String,
    value: String?,
    trailing: RowTrailing,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    indented: Boolean = false,
    colors: ClickableSurfaceColors = settingsRowColors(),
) {
    val stepper = trailing as? RowTrailing.Stepper
    if (indented) {
        Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .padding(start = ConnectorStart, end = ConnectorGap)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(OutlineDim, RoundedCornerShape(1.dp)),
            )
            SettingsRowSurface(icon, title, value, trailing, stepper, onClick, colors, iconSize = 20.dp, modifier = Modifier.weight(1f))
        }
    } else {
        SettingsRowSurface(icon, title, value, trailing, stepper, onClick, colors, iconSize = 24.dp, modifier = modifier.fillMaxWidth())
    }
}

@Composable
private fun SettingsRowSurface(
    icon: ImageVector?,
    title: String,
    value: String?,
    trailing: RowTrailing,
    stepper: RowTrailing.Stepper?,
    onClick: () -> Unit,
    colors: ClickableSurfaceColors,
    iconSize: Dp,
    modifier: Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = RowMinHeight)
            .then(
                if (stepper != null) {
                    Modifier
                        .onDpadKeyDown(Key.DirectionLeft) { stepper.onDecrease(); true }
                        .onDpadKeyDown(Key.DirectionRight) { stepper.onIncrease(); true }
                } else {
                    Modifier
                },
            ),
        shape = ClickableSurfaceDefaults.shape(RowShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = colors,
    ) {
        Row(
            Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize)) else Spacer(Modifier.size(iconSize))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!value.isNullOrBlank() && stepper == null) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when (trailing) {
                RowTrailing.Chevron -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(26.dp))
                is RowTrailing.Toggle -> ToggleSwitch(trailing.checked)
                is RowTrailing.Stepper -> StepperValue(value.orEmpty())
                RowTrailing.None -> Unit
            }
        }
    }
}

@Composable
fun settingsRowColors() = focusPillColors(containerColor = Color.White.copy(alpha = 0.10f))

/** Rows inside the Settings category card: transparent at rest (the card is the surface), light pill on focus. */
@Composable
fun settingsCardRowColors() = focusPillColors(containerColor = Color.Transparent)

/** Non-interactive switch drawn inside a row; the whole row toggles it. */
@Composable
fun ToggleSwitch(checked: Boolean) {
    val thumbOffset by animateDpAsState(if (checked) ThumbTravel else 0.dp, label = "switchThumb")
    Box(
        Modifier
            .size(width = 46.dp, height = 26.dp)
            .background(if (checked) SuccessGreen else Color.Gray.copy(alpha = 0.55f), CircleShape)
            .padding(4.dp),
    ) {
        Box(Modifier.offset(x = thumbOffset).size(18.dp).background(Color.White, CircleShape))
    }
}

@Composable
private fun StepperValue(value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Decrease", modifier = Modifier.size(22.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Increase", modifier = Modifier.size(22.dp))
    }
}

/** Uppercase section label (`ADDONS & PORTAL`, `PLAYBACK`, …). */
@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = TextSecondary,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 14.dp, bottom = 2.dp),
    )
}

/** Compact uppercase category label with a small leading icon (`PLAYBACK` in `settings-top-nav.jpg`). */
@Composable
fun SettingsCategoryHeader(category: SettingsCategory, modifier: Modifier = Modifier) {
    SettingsSectionLabel(category.icon, category.label, modifier)
}

/** The uppercase label-with-icon style shared by category headers and sub-screens (`SUBTITLE STYLING`). */
@Composable
fun SettingsSectionLabel(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(start = 4.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = TextSecondary,
        )
    }
}

private val RowMinHeight = 58.dp
private val ConnectorStart = 34.dp
private val ConnectorGap = 14.dp
private val RowShape = RoundedCornerShape(29.dp)
private val ThumbTravel = 20.dp
