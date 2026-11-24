package com.seastreams.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.seastreams.tv.core.util.MILLIS_PER_SECOND
import com.seastreams.tv.data.usenet.headers.HeaderUse
import com.seastreams.tv.ui.screens.settings.sources.HEADERS_DOWNLOAD_ROW_KEY
import com.seastreams.tv.ui.screens.settings.sources.HEADERS_SEARCH_ROW_KEY
import com.seastreams.tv.ui.screens.settings.sources.INDEXERS_ROW_KEY
import com.seastreams.tv.ui.screens.settings.sources.PERFORMANCE_ROW_KEY
import com.seastreams.tv.ui.screens.settings.sources.PROVIDERS_ROW_KEY
import com.seastreams.tv.ui.screens.settings.sources.DEBRID_PROVIDERS_ROW_KEY
import com.seastreams.tv.ui.screens.settings.sources.DebridSourcesViewModel
import com.seastreams.tv.ui.screens.settings.sources.SourcesActions
import com.seastreams.tv.ui.screens.settings.sources.SourcesPanelHost
import com.seastreams.tv.ui.screens.settings.sources.SourcesViewModel
import com.seastreams.tv.ui.screens.settings.streams.STREAM_COPY_ROW_KEY
import com.seastreams.tv.ui.screens.settings.streams.STREAM_FILTERS_ROW_KEY
import com.seastreams.tv.ui.screens.settings.streams.STREAM_FORMATTER_ROW_KEY
import com.seastreams.tv.ui.screens.settings.streams.STREAM_SORT_ROW_KEY
import com.seastreams.tv.ui.screens.settings.streams.StreamPanelHost
import com.seastreams.tv.ui.screens.settings.streams.StreamProcessingActions
import com.seastreams.tv.ui.screens.settings.streams.StreamProcessingViewModel
import com.seastreams.tv.ui.screens.settings.streams.streamReturnRow
import com.seastreams.tv.ui.components.LocalFocusOrigins
import com.seastreams.tv.ui.components.LocalLayerCovered
import com.seastreams.tv.ui.components.LocalTransientMessages
import com.seastreams.tv.ui.screens.playback.RESTORE_FRAMES
import com.seastreams.tv.ui.theme.AppearanceViewModel
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.retryEachFrame
import com.seastreams.tv.ui.theme.tryRequestFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * General Settings (`settings-top-nav.jpg`, PRD §11): a full-screen page (the main nav bar stays
 * hidden, no title header) with the Settings Category Tab Bar on top and, below it, the active
 * category's rows or one of their sub-panels. Opening focuses the `Home` tab. `Back` closes a
 * sub-panel onto its row, moves from a row to the active tab, and on a tab falls through to the
 * shell, which closes Settings and refocuses the `Settings` gear ([settingsBackAction]).
 */
@Composable
fun SettingsScreen(
    profileId: Long,
    viewModel: SettingsViewModel,
    appearance: AppearanceViewModel,
    sources: SourcesViewModel,
    debrid: DebridSourcesViewModel,
    streams: StreamProcessingViewModel,
    onOpenProfiles: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val sourcesState by sources.state.collectAsState()
    val debridState by debrid.state.collectAsState()
    val streamState by streams.state.collectAsState()
    val streamProfiles by streams.profiles.collectAsState()
    val savedBackground by appearance.saved.collectAsState()
    val cacheBytes by viewModel.cacheBytes.collectAsState()
    val messages = LocalTransientMessages.current
    val origins = LocalFocusOrigins.current
    val covered = LocalLayerCovered.current
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf(SettingsCategory.HOME) }
    var panel by remember { mutableStateOf<SettingsPanel>(SettingsPanel.Main) }
    var returnKey by remember { mutableStateOf<String?>(null) }
    var swatchesOpen by remember { mutableStateOf(false) }
    var listHasFocus by remember { mutableStateOf(false) }
    val pillRequesters = remember { SettingsCategory.entries.map { FocusRequester() } }
    // A fresh list per category, so each one opens scrolled to its top.
    val listState = remember(category) { LazyListState() }
    val rowRequesters = remember { HashMap<String, FocusRequester>() }
    val requesterFor: (String) -> FocusRequester = { key -> rowRequesters.getOrPut(key) { FocusRequester() } }
    val focusRow: (String) -> Unit = { key -> scope.launch { retryEachFrame(RESTORE_FRAMES) { requesterFor(key).tryRequestFocus() } } }

    LaunchedEffect(viewModel) {
        viewModel.refreshPlayers()
        viewModel.refreshCacheSize()
    }
    LaunchedEffect(viewModel) { viewModel.messages.collect { messages?.show(it) } }
    LaunchedEffect(sources) { sources.messages.collect { messages?.show(it) } }
    LaunchedEffect(debrid) { debrid.messages.collect { messages?.show(it) } }
    LaunchedEffect(streams) { streams.messages.collect { messages?.show(it) } }
    // Opening the Sources tab runs the daily header-version check when it's due (PRD §12).
    LaunchedEffect(category) { if (category == SettingsCategory.SOURCES) sources.checkHeadersIfDue() }
    LaunchedEffect(Unit) { pillRequesters[SettingsCategory.HOME.ordinal].requestFocusAfterFrame() }
    // Leaving a panel: land on the row that opened it.
    LaunchedEffect(panel) {
        viewModel.setPortalVisible(panel == SettingsPanel.Portal)
        if (panel == SettingsPanel.Main) returnKey?.let { focusRow(streamReturnRow(it, streamState)) }
    }
    val openPanel: (String, SettingsPanel) -> Unit = { key, next ->
        returnKey = key
        panel = next
    }
    val backAction = settingsBackAction(panelOpen = panel != SettingsPanel.Main, swatchesOpen = swatchesOpen, focusInList = listHasFocus)
    BackHandler(enabled = !covered && backAction == SettingsBackAction.CLOSE_PANEL) { panel = panel.parent() }
    // REVERT_SWATCHES is the strip's own BackHandler; EXIT is ProfileShell's cover BackHandler.
    BackHandler(enabled = !covered && backAction == SettingsBackAction.FOCUS_ACTIVE_PILL) {
        pillRequesters[category.ordinal].tryRequestFocus()
    }

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
    // Ticks while a `Clear Usenet / Debrid settings` can still be undone, so its row counts down.
    val sourceDeadlines = listOfNotNull(sourcesState.clearDeadlineMillis, debridState.clearDeadlineMillis)
    val sourcesNow by produceState(System.currentTimeMillis(), sourceDeadlines) {
        do {
            value = System.currentTimeMillis()
            if (sourceDeadlines.isNotEmpty()) delay(TICK_MILLIS)
        } while (sourceDeadlines.isNotEmpty())
    }
    val items = settingsItems(
        category = category,
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
            openProfiles = {
                // Back on the profile switcher returns focus to this row (FocusOrigin, PRD §2).
                origins?.mark(requesterFor(PROFILES_ROW_KEY))
                onOpenProfiles()
            },
            updateHeaders = sources::updateHeadersNow,
            sources = SourcesActions(
                setUsenetEnabled = { sources.setEnabled(it) },
                clearUsenet = { sources.clearAll() },
                undoClearUsenet = { sources.undoClear() },
                setDebridEnabled = { debrid.setEnabled(it) },
                clearDebrid = { debrid.clearAll() },
                undoClearDebrid = { debrid.undoClear() },
            ),
            streams = StreamProcessingActions(
                setFilteringEnabled = streams::setFilteringEnabled,
                setSortingEnabled = streams::setSortingEnabled,
                setFormattingEnabled = streams::setFormattingEnabled,
            ),
        ),
        sources = sourcesState,
        debrid = debridState,
        streams = streamState,
        profileCount = streamProfiles.size,
        nowMillis = sourcesNow,
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 36.dp),
    ) {
        SettingsCategoryBar(
            selected = category,
            requesters = pillRequesters,
            focusable = panel == SettingsPanel.Main,
            onSelect = { next ->
                if (next != category) swatchesOpen = false
                category = next
            },
            onDown = {
                scope.launch {
                    listState.scrollToItem(0)
                    requesterFor(firstEntryKey(items)).requestFocusAfterFrame()
                }
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            when (val current = panel) {
                SettingsPanel.Main -> SettingsCategoryList(
                    category = category,
                    items = items,
                    listState = listState,
                    requesterFor = requesterFor,
                    onFocusInListChanged = { listHasFocus = it },
                    onUpFromFirst = { pillRequesters[category.ordinal].tryRequestFocus() },
                    modifier = Modifier.fillMaxSize().padding(bottom = 32.dp),
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
                is SettingsPanel.Stream -> StreamPanelHost(
                    panel = current,
                    settings = streamState,
                    viewModel = streams,
                    open = { next -> openPanel(keyFor(next), next) },
                    openPortal = { panel = SettingsPanel.Portal },
                    close = { panel = SettingsPanel.Main },
                    nowMillis = System.currentTimeMillis(),
                    profiles = streamProfiles,
                    activeProfileId = profileId,
                )
                SettingsPanel.UsenetProviders,
                SettingsPanel.UsenetIndexers,
                SettingsPanel.UsenetPerformance,
                SettingsPanel.DebridProviders,
                is SettingsPanel.Headers,
                -> SourcesPanelHost(
                    panel = current,
                    usenetState = sourcesState,
                    usenet = sources,
                    debridState = debridState,
                    debrid = debrid,
                    openPortal = { panel = SettingsPanel.Portal },
                    close = { panel = SettingsPanel.Main },
                )
                SettingsPanel.SubtitleStyle -> SubtitleStyleScreen(state.settings.subtitles.style) { transform ->
                    viewModel.updateSubtitles { it.copy(style = transform(it.style)) }
                }
            }
        }
    }
}

/** The row each panel is opened from, so `Back` returns focus there. */
private fun keyFor(panel: SettingsPanel): String = when (panel) {
    SettingsPanel.Addons -> ADDONS_ROW_KEY
    SettingsPanel.Portal -> PORTAL_ROW_KEY
    SettingsPanel.SubtitleStyle -> "subs-style"
    SettingsPanel.UsenetProviders -> PROVIDERS_ROW_KEY
    SettingsPanel.UsenetIndexers -> INDEXERS_ROW_KEY
    SettingsPanel.UsenetPerformance -> PERFORMANCE_ROW_KEY
    SettingsPanel.DebridProviders -> DEBRID_PROVIDERS_ROW_KEY
    is SettingsPanel.Headers -> if (panel.use == HeaderUse.SEARCH) HEADERS_SEARCH_ROW_KEY else HEADERS_DOWNLOAD_ROW_KEY
    SettingsPanel.Stream.Sort -> STREAM_SORT_ROW_KEY
    SettingsPanel.Stream.Formatter -> STREAM_FORMATTER_ROW_KEY
    SettingsPanel.Stream.CopyFrom -> STREAM_COPY_ROW_KEY
    // Nested filter panels all return to the `Stream filters` row once Back reaches the list.
    is SettingsPanel.Stream -> STREAM_FILTERS_ROW_KEY
    is SettingsPanel.Choice -> when (panel.kind) {
        ChoiceKind.PREFERRED_ADDON -> "autoplay-addon"
        ChoiceKind.PLAYER -> PLAYER_ROW_KEY
        ChoiceKind.DIALOGUE_BOOST -> "dialogue"
        ChoiceKind.AUDIO_LANGUAGE -> "audio-lang"
        ChoiceKind.SUBTITLE_LANGUAGE -> "subs-lang"
        ChoiceKind.BUFFER_PROFILE -> "buffer"
        ChoiceKind.PARALLEL_CONNECTIONS -> "parallel"
        ChoiceKind.DOLBY_VISION -> "dolby-vision"
        ChoiceKind.PLAYBACK_SPEED -> SPEED_ROW_KEY
        ChoiceKind.VOLUME_BOOST -> VOLUME_ROW_KEY
    }
    SettingsPanel.Main -> PROFILES_ROW_KEY
}

private val PAGE_GUTTER = 56.dp
private const val TICK_MILLIS = 250L
