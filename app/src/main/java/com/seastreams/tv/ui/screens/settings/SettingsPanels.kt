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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
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
import com.seastreams.tv.ui.theme.Follow
import com.seastreams.tv.ui.theme.focusItem
import com.seastreams.tv.ui.theme.ignoreInitialKeyUp
import com.seastreams.tv.ui.theme.rememberListEdgeWrap
import com.seastreams.tv.ui.theme.rememberReorderFocus
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.tryRequestFocus
import com.seastreams.tv.ui.theme.verticalEdgeKeys
import com.seastreams.tv.ui.theme.verticalListEdgeWrap
import kotlinx.coroutines.launch

/**
 * Radio-style list; picking an option saves it and returns to the main list via [onDone]. Rows ignore a release whose
 * `OK` press began elsewhere ([ignoreInitialKeyUp]), so the key-up of the press that opened the panel never picks one.
 */
@Composable
fun ChoicePanel(spec: ChoiceSpec, onDone: () -> Unit) {
    val selected = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // Scroll to the chosen option first: an off-screen row has no attached requester yet.
    LaunchedEffect(Unit) {
        if (!listState.focusItem(spec.options.indexOfFirst { it.selected }, selected)) listState.focusItem(0, first)
    }
    PanelColumn(spec.title, listState) {
        itemsIndexed(spec.options, key = { _, option -> option.key ?: option.label }) { index, option ->
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
                    .ignoreInitialKeyUp()
                    .then(if (option.selected) Modifier.focusRequester(selected) else Modifier)
                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier),
            )
        }
    }
}

/**
 * Enable/disable, reorder and remove installed Stremio addons. New ones are added from the Web Portal.
 *
 * Two-way §1 edge-stop-then-wrap between the first addon row and the closing `Install more addons`
 * row: every control of the first row (toggle, move up/down, remove) owns `Up` and wraps to
 * `Install more addons`; a fresh `Down` there wraps to the first row's toggle. Held keys stop.
 *
 * `Remove` first moves focus to [addonRemovalFocus]'s target (the next addon's `Remove`, or its toggle
 * for a built-in; `Install more addons` after the last addon), then removes, so focus is never lost.
 */
@Composable
fun AddonsPanel(
    addons: List<AddonEntity>,
    onToggle: (AddonEntity) -> Unit,
    onMove: (AddonEntity, Int) -> Unit,
    onRemove: (AddonEntity) -> Unit,
    onOpenPortal: () -> Unit,
) {
    val listState = rememberLazyListState()
    val first = remember { FocusRequester() }
    val last = remember { FocusRequester() }
    val lastIndex = addonsPanelLastIndex(addons.size)
    val wrap = rememberListEdgeWrap(listState, first, last, lastIndex)
    LaunchedEffect(Unit) { first.requestFocusAfterFrame() }
    val scope = rememberCoroutineScope()
    // Move up / Move down keep the moved row in view and its button focused once the new order is saved.
    val reorder = rememberReorderFocus<String>(listState)
    val urls = addons.map { it.manifestUrl }
    reorder.Follow(urls)
    // Rows are keyed by manifest URL, so a row's requesters follow it when the rows above change.
    val rowFocus = remember { HashMap<String, AddonRowFocus>() }
    val focusOf: (AddonEntity) -> AddonRowFocus = { rowFocus.getOrPut(it.manifestUrl) { AddonRowFocus() } }
    val removeKeepingFocus: (Int, AddonEntity) -> Unit = { index, addon ->
        val (targetIndex, target) = when (
            val next = addonRemovalFocus(index, addons.size) { !BuiltInAddons.isProtected(addons[it].manifestUrl) }
        ) {
            is AddonRemovalFocus.Row -> next.index to focusOf(addons[next.index]).let {
                if (next.control == AddonRowControl.REMOVE) it.remove else it.toggle
            }
            AddonRemovalFocus.InstallMore -> lastIndex to last
        }
        scope.launch {
            if (!target.tryRequestFocus()) {
                listState.scrollToItem(targetIndex)
                target.requestFocusAfterFrame()
            }
            onRemove(addon)
        }
    }
    PanelColumn("Manage addons", listState) {
        itemsIndexed(addons, key = { _, addon -> addon.manifestUrl }) { index, addon ->
            val builtIn = BuiltInAddons.isProtected(addon.manifestUrl)
            val edge = Modifier.verticalListEdgeWrap(index, lastIndex, wrap.toFirst, wrap.toLast)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsRow(
                    icon = SeaIcons.Addons,
                    title = addon.displayName(),
                    value = addonStateLabel(builtIn, BuiltInAddons.canDisable(addon.manifestUrl), addon.enabled),
                    trailing = RowTrailing.Toggle(addon.enabled),
                    onClick = { onToggle(addon) },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusOf(addon).toggle)
                        .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                        .then(edge),
                )
                RoundIconButton(Icons.Filled.KeyboardArrowUp, "Move up", edge.focusRequester(reorder.up(addon.manifestUrl))) {
                    reorder.onMove(addon.manifestUrl, -1, urls)
                    onMove(addon, -1)
                }
                RoundIconButton(Icons.Filled.KeyboardArrowDown, "Move down", edge.focusRequester(reorder.down(addon.manifestUrl))) {
                    reorder.onMove(addon.manifestUrl, 1, urls)
                    onMove(addon, 1)
                }
                if (!builtIn) {
                    RoundIconButton(Icons.Filled.Close, "Remove", edge.focusRequester(focusOf(addon).remove)) {
                        removeKeepingFocus(index, addon)
                    }
                }
            }
        }
        item(key = "portal-hint") {
            SettingsRow(
                icon = SeaIcons.QrCode,
                title = "Install more addons",
                value = "Paste an addon manifest URL in the Web Portal",
                trailing = RowTrailing.Chevron,
                onClick = onOpenPortal,
                modifier = Modifier
                    .focusRequester(last)
                    // With no addons this is the only row, so it is also the first (initial focus + wraps).
                    .then(if (addons.isEmpty()) Modifier.focusRequester(first) else Modifier)
                    .verticalListEdgeWrap(lastIndex, lastIndex, wrap.toFirst, wrap.toLast),
            )
        }
    }
}

/** `Built-in` (TMDB), `Built-in · Enabled` (Usenet), else `Enabled` / `Disabled`. */
internal fun addonStateLabel(builtIn: Boolean, canDisable: Boolean, enabled: Boolean): String {
    val state = if (enabled) "Enabled" else "Disabled"
    return when {
        builtIn && canDisable -> "Built-in · $state"
        builtIn -> "Built-in"
        else -> state
    }
}

/** Focus handles of one Manage addons row: its toggle pill and (non-built-ins only) its `Remove` button. */
private class AddonRowFocus {
    val toggle = FocusRequester()
    val remove = FocusRequester()
}

/** Lazy index of the closing `Install more addons` row, the last item of the Manage addons list. */
internal fun addonsPanelLastIndex(addonCount: Int): Int = addonCount

/** The edge keys Manage addons list item [index] owns (addon rows `0 until addonCount`, then the portal row). */
internal fun addonsPanelEdgeKeys(index: Int, addonCount: Int): Set<Key> =
    verticalEdgeKeys(index, addonsPanelLastIndex(addonCount))

/** QR code + LAN URL of the Web Portal (PRD §8). */
@Composable
fun PortalPanel(link: PortalLink?, caption: String = PORTAL_CAPTION, onDone: () -> Unit) {
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
                            caption,
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
internal fun RoundIconButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(46.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = settingsRowColors(),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.padding(11.dp))
    }
}

@Composable
internal fun PanelColumn(
    title: String,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 40.dp, bottom = 12.dp, start = 8.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 40.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** What the Web Portal panel says the portal is for. */
const val PORTAL_CAPTION = "on a phone or computer on the same Wi-Fi to install addons, import MDBList lists into a Library and edit profiles."

/** The same panel opened from `Other → Backup & restore`. */
const val BACKUP_PORTAL_CAPTION =
    "on a phone or computer on the same Wi-Fi, then use Backup & restore to download a .json backup of this profile or the whole app, or to restore one."
