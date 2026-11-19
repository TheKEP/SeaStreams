package com.seastreams.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.core.util.MILLIS_PER_SECOND
import com.seastreams.tv.ui.components.LocalTransientMessages
import com.seastreams.tv.ui.screens.playback.RESTORE_FRAMES
import com.seastreams.tv.ui.theme.AppearanceViewModel
import com.seastreams.tv.ui.theme.rememberListEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.retryEachFrame
import com.seastreams.tv.ui.theme.tryRequestFocus
import com.seastreams.tv.ui.theme.verticalListEdgeWrap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * General Settings (`settings-example.jpg`): centred gear + `Settings` on the left ~30%; on the right a
 * scrolling list of pill rows under section headers, or one of its sub-panels. `Back` in a sub-panel
 * returns to the row that opened it; `Back` on the main list closes Settings (shell).
 */
@Composable
fun SettingsScreen(profileId: Long, viewModel: SettingsViewModel, appearance: AppearanceViewModel) {
    val state by viewModel.state.collectAsState()
    val savedBackground by appearance.saved.collectAsState()
    val cacheBytes by viewModel.cacheBytes.collectAsState()
    val messages = LocalTransientMessages.current
    val scope = rememberCoroutineScope()
    var panel by remember { mutableStateOf<SettingsPanel>(SettingsPanel.Main) }
    var returnKey by remember { mutableStateOf<String?>(null) }
    var swatchesOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val rowRequesters = remember { HashMap<String, FocusRequester>() }
    val requesterFor: (String) -> FocusRequester = { key -> rowRequesters.getOrPut(key) { FocusRequester() } }
    val focusRow: (String) -> Unit = { key -> scope.launch { retryEachFrame(RESTORE_FRAMES) { requesterFor(key).tryRequestFocus() } } }

    LaunchedEffect(viewModel) {
        viewModel.refreshPlayers()
        viewModel.refreshCacheSize()
    }
    LaunchedEffect(viewModel) { viewModel.messages.collect { messages?.show(it) } }
    LaunchedEffect(Unit) { requesterFor(FIRST_ROW_KEY).requestFocusAfterFrame() }
    // Leaving a panel: land on the row that opened it.
    LaunchedEffect(panel) {
        viewModel.setPortalVisible(panel == SettingsPanel.Portal)
        if (panel == SettingsPanel.Main) returnKey?.let(focusRow)
    }
    val openPanel: (String, SettingsPanel) -> Unit = { key, next ->
        returnKey = key
        panel = next
    }
    BackHandler(enabled = panel != SettingsPanel.Main) { panel = SettingsPanel.Main }

    val pending = state.pendingClear?.takeIf { it.profileId == profileId }
    val clearSecondsLeft by produceState<Int?>(null, pending) {
        if (pending == null) {
            value = null
            return@produceState
        }
        while (true) {
            val left = ((pending.deadlineMillis - System.currentTimeMillis() + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()
            value = left.coerceAtLeast(0)
            if (left <= 0) break
            delay(TICK_MILLIS)
        }
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(LEFT_WEIGHT).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(64.dp))
                Text("Settings", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            }
        }
        Box(Modifier.weight(1f - LEFT_WEIGHT).fillMaxHeight().padding(end = 56.dp)) {
            when (val current = panel) {
                SettingsPanel.Main -> MainList(
                    items = settingsItems(
                        state = state,
                        swatchesOpen = swatchesOpen,
                        clearSecondsLeft = clearSecondsLeft,
                        cacheBytes = cacheBytes,
                        cb = SettingsCallbacks(
                            open = { next -> openPanel(keyFor(next), next) },
                            toggleSwatches = { swatchesOpen = !swatchesOpen },
                            updatePlayer = viewModel::updatePlayer,
                            vm = viewModel,
                            clearLibrary = { viewModel.clearLibrary(profileId) },
                            undoClear = viewModel::undoClearLibrary,
                            clearCache = viewModel::clearCache,
                        ),
                    ),
                    listState = listState,
                    requesterFor = requesterFor,
                    swatches = {
                        BackgroundSwatchStrip(
                            savedArgb = savedBackground,
                            onPreview = appearance::preview,
                            onConfirm = { argb ->
                                appearance.confirm(argb)
                                swatchesOpen = false
                                focusRow(BACKGROUND_ROW_KEY)
                            },
                            onCancel = { refocusRow ->
                                appearance.cancelPreview()
                                swatchesOpen = false
                                if (refocusRow) focusRow(BACKGROUND_ROW_KEY)
                            },
                        )
                    },
                )
                is SettingsPanel.Choice -> ChoicePanel(choiceSpec(current.kind, state, viewModel)) { panel = SettingsPanel.Main }
                SettingsPanel.Addons -> AddonsPanel(
                    addons = state.addons,
                    onToggle = { viewModel.setAddonEnabled(it.manifestUrl, !it.enabled) },
                    onMove = { addon, offset -> viewModel.moveAddon(addon.manifestUrl, offset) },
                    onRemove = { viewModel.removeAddon(it.manifestUrl) },
                    onOpenPortal = { panel = SettingsPanel.Portal },
                )
                SettingsPanel.Portal -> PortalPanel(state.portal) { panel = SettingsPanel.Main }
                SettingsPanel.SubtitleStyle -> SubtitleStyleScreen(state.settings.subtitles.style) { transform ->
                    viewModel.updateSubtitles { it.copy(style = transform(it.style)) }
                }
            }
        }
    }
}

@Composable
private fun MainList(
    items: List<SettingsItem>,
    listState: LazyListState,
    requesterFor: (String) -> FocusRequester,
    swatches: @Composable () -> Unit,
) {
    // Two-way §1 wrap between the first and last focusable rows; headers are skipped. Wrapping up
    // scrolls to item 0 so the `ADDONS & PORTAL` header shows above the first row.
    val ordinals = remember(items) { settingsEntryOrdinals(items) }
    val lastOrdinal = ordinals.maxOrNull() ?: -1
    val firstKey = items.firstOrNull { it is SettingsItem.Entry }?.key ?: FIRST_ROW_KEY
    val lastKey = items.lastOrNull { it is SettingsItem.Entry }?.key ?: FIRST_ROW_KEY
    val wrap = rememberListEdgeWrap(listState, requesterFor(firstKey), requesterFor(lastKey), items.lastIndex)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 32.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
            when (item) {
                is SettingsItem.Header -> SettingsSectionHeader(item.title)
                SettingsItem.Swatches -> swatches()
                is SettingsItem.Entry -> SettingsRow(
                    icon = item.icon,
                    title = item.title,
                    value = item.value,
                    trailing = item.trailing,
                    onClick = item.onClick,
                    modifier = Modifier
                        .focusRequester(requesterFor(item.key))
                        .verticalListEdgeWrap(ordinals[index], lastOrdinal, wrap.toFirst, wrap.toLast),
                )
            }
        }
    }
}

/** The main-list row each panel is opened from, so `Back` returns focus there. */
private fun keyFor(panel: SettingsPanel): String = when (panel) {
    SettingsPanel.Addons -> "addons"
    SettingsPanel.Portal -> "portal"
    SettingsPanel.SubtitleStyle -> "subs-style"
    is SettingsPanel.Choice -> when (panel.kind) {
        ChoiceKind.PREFERRED_ADDON -> "autoplay-addon"
        ChoiceKind.PLAYER -> "player"
        ChoiceKind.DIALOGUE_BOOST -> "dialogue"
        ChoiceKind.AUDIO_LANGUAGE -> "audio-lang"
        ChoiceKind.SUBTITLE_LANGUAGE -> "subs-lang"
        ChoiceKind.BUFFER_PROFILE -> "buffer"
        ChoiceKind.PARALLEL_CONNECTIONS -> "parallel"
    }
    SettingsPanel.Main -> FIRST_ROW_KEY
}

private const val FIRST_ROW_KEY = "addons"
private const val LEFT_WEIGHT = 0.3f
private const val TICK_MILLIS = 250L
