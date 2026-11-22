package com.seastreams.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.ui.theme.FocusPill
import com.seastreams.tv.ui.theme.OnFocusPill
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.onDpadKeyDown
import com.seastreams.tv.ui.theme.tryRequestFocus

/**
 * The Settings Category Tab Bar (`settings-top-nav.jpg`): a centred translucent pill container
 * holding `Home`, `UI`, `Playback`, `Audio`, `Subtitles`, `Other`. Moving focus onto a tab selects
 * it at once. `Home` ⇄ `Other` wrap on a fresh `Left`/`Right` (held keys stop, §1); `Up` is
 * consumed (the main nav bar is hidden here); `Down` runs [onDown] (focus the list's first row).
 * While [focusable] is false (a sub-panel is open) no tab can take focus.
 */
@Composable
fun SettingsCategoryBar(
    selected: SettingsCategory,
    requesters: List<FocusRequester>,
    focusable: Boolean,
    onSelect: (SettingsCategory) -> Unit,
    onDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val categories = SettingsCategory.entries
    Row(
        modifier
            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(50))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        categories.forEachIndexed { index, category ->
            var tabModifier = Modifier
                .focusRequester(requesters[index])
                .focusProperties { canFocus = focusable }
                .onFocusChanged { if (it.isFocused) onSelect(category) }
                .onDpadKeyDown(Key.DirectionUp) { true }
                .onDpadKeyDown(Key.DirectionDown) {
                    onDown()
                    true
                }
            for (key in categoryEdgeKeys(index)) {
                val target = categoryWrapTarget(index, key) ?: continue
                tabModifier = tabModifier.onDpadEdgeWrap(key) { requesters[target].tryRequestFocus() }
            }
            CategoryTab(category.label, selected = category == selected, modifier = tabModifier)
        }
    }
}

@Composable
private fun CategoryTab(label: String, selected: Boolean, modifier: Modifier) {
    Surface(
        // OK on a tab does nothing extra: focusing it already selected it.
        onClick = {},
        modifier = modifier.height(44.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) FocusPill else Color.Transparent,
            contentColor = if (selected) OnFocusPill else TextSecondary,
            focusedContainerColor = FocusPill,
            focusedContentColor = OnFocusPill,
        ),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }
}
