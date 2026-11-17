package com.seastreams.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.webserver.PortalLink
import com.seastreams.tv.data.addon.BuiltInAddons
import com.seastreams.tv.ui.components.QrCode
import com.seastreams.tv.ui.components.SeaIcons
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.requestFocusAfterFrame

/** Radio-style list; picking an option saves it and returns to the main list via [onDone]. */
@Composable
fun ChoicePanel(spec: ChoiceSpec, onDone: () -> Unit) {
    val selected = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (!selected.requestFocusAfterFrame()) first.requestFocusAfterFrame() }
    PanelColumn(spec.title) {
        itemsIndexed(spec.options, key = { _, option -> option.label }) { index, option ->
            SettingsRow(
                icon = if (option.selected) Icons.Filled.Check else null,
                title = option.label,
                value = if (option.selected) "Selected" else null,
                trailing = RowTrailing.None,
                onClick = {
                    option.apply()
                    onDone()
                },
                modifier = Modifier
                    .then(if (option.selected) Modifier.focusRequester(selected) else Modifier)
                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier),
            )
        }
    }
}

/** Enable/disable, reorder and remove installed Stremio addons. New ones are added from the Web Portal. */
@Composable
fun AddonsPanel(
    addons: List<AddonEntity>,
    onToggle: (AddonEntity) -> Unit,
    onMove: (AddonEntity, Int) -> Unit,
    onRemove: (AddonEntity) -> Unit,
    onOpenPortal: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusAfterFrame() }
    PanelColumn("Manage addons") {
        itemsIndexed(addons, key = { _, addon -> addon.manifestUrl }) { index, addon ->
            val builtIn = BuiltInAddons.isProtected(addon.manifestUrl)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsRow(
                    icon = SeaIcons.Addons,
                    title = addon.displayName(),
                    value = if (builtIn) "Built-in" else if (addon.enabled) "Enabled" else "Disabled",
                    trailing = RowTrailing.Toggle(addon.enabled),
                    onClick = { onToggle(addon) },
                    modifier = Modifier.weight(1f).then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                )
                RoundIconButton(Icons.Filled.KeyboardArrowUp, "Move up") { onMove(addon, -1) }
                RoundIconButton(Icons.Filled.KeyboardArrowDown, "Move down") { onMove(addon, 1) }
                if (!builtIn) RoundIconButton(Icons.Filled.Close, "Remove") { onRemove(addon) }
            }
        }
        item(key = "portal-hint") {
            SettingsRow(
                icon = SeaIcons.QrCode,
                title = "Install more addons",
                value = "Paste an addon manifest URL in the Web Portal",
                trailing = RowTrailing.Chevron,
                onClick = onOpenPortal,
            )
        }
    }
}

/** QR code + LAN URL of the Web Portal (PRD §8). */
@Composable
fun PortalPanel(link: PortalLink?, onDone: () -> Unit) {
    val done = remember { FocusRequester() }
    LaunchedEffect(Unit) { done.requestFocusAfterFrame() }
    PanelColumn("Web Portal") {
        item(key = "portal") {
            val url = link?.url
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                if (url != null) QrCode(text = url, modifier = Modifier.size(180.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (url != null) {
                        Text("Scan the code or open", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Text(url, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "on a phone or computer on the same Wi-Fi to install addons, import MDBList lists into a Library and edit profiles.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    } else {
                        Text(link?.problem ?: "Starting Web Portal…", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        item(key = "done") {
            SettingsRow(Icons.Filled.Check, "Done", null, RowTrailing.None, onDone, Modifier.focusRequester(done))
        }
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(46.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = settingsRowColors(),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.padding(11.dp))
    }
}

@Composable
private fun PanelColumn(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 40.dp, bottom = 12.dp, start = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 40.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}
