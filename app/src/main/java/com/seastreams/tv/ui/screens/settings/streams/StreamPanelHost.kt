package com.seastreams.tv.ui.screens.settings.streams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.domain.streams.FilterList
import com.seastreams.tv.domain.streams.StreamProcessingSettings
import com.seastreams.tv.ui.screens.settings.ChoiceOption
import com.seastreams.tv.ui.screens.settings.ChoicePanel
import com.seastreams.tv.ui.screens.settings.ChoiceSpec
import com.seastreams.tv.ui.screens.settings.SectionedRowsPanel
import com.seastreams.tv.ui.screens.settings.SettingsPanel

/**
 * Shows one `UI` stream-processing panel (PRD §13) for the active profile. `Back` (the Settings screen's panel
 * BackHandler, via [SettingsPanel.parent]) walks Values → Group → Filters → the `UI` list, and each parent panel
 * reopens on the row its child was opened from. `Copy stream settings from…` lists [profiles] other than the active
 * [activeProfileId]; a pick copies and returns to its row.
 */
@Composable
fun StreamPanelHost(
    panel: SettingsPanel.Stream,
    settings: StreamProcessingSettings,
    viewModel: StreamProcessingViewModel,
    open: (SettingsPanel) -> Unit,
    openPortal: () -> Unit,
    close: () -> Unit,
    nowMillis: Long,
    profiles: List<ProfileEntity> = emptyList(),
    activeProfileId: Long? = null,
) {
    var shown by remember { mutableStateOf(panel) }
    // The panel shown before this one (the child we came back from), read when [panel] changes.
    val returnFrom = remember(panel) { shown.takeIf { it != panel } }
    SideEffect { shown = panel }
    val filters = settings.filters
    when (panel) {
        SettingsPanel.Stream.Filters -> SectionedRowsPanel(
            title = "Stream filters",
            items = streamFilterItems(filters, open, openPortal, viewModel::updateFilters),
            initialKey = (returnFrom as? SettingsPanel.Stream.FilterGroup)?.let { attributeKey(it.attribute) },
        )
        is SettingsPanel.Stream.FilterGroup -> SectionedRowsPanel(
            title = panel.attribute.label,
            items = filterGroupItems(panel.attribute, filters.attribute(panel.attribute), open),
            initialKey = (returnFrom as? SettingsPanel.Stream.FilterValues)?.let { listKey(it.list) },
        )
        is SettingsPanel.Stream.FilterValues -> MultiChoicePanel(
            title = "${panel.list.label} ${plural(panel.attribute)}",
            options = panel.attribute.options.orEmpty(),
            selected = filters.attribute(panel.attribute).list(panel.list),
            ordered = panel.list == FilterList.PREFERRED,
            onChange = { values ->
                viewModel.updateFilters { f -> f.withAttribute(panel.attribute, f.attribute(panel.attribute).with(panel.list, values)) }
            },
        )
        SettingsPanel.Stream.Sort -> StreamSortPanel(settings.sort, viewModel::updateSort)
        SettingsPanel.Stream.Formatter -> StreamFormatterPanel(
            choice = settings.formatter,
            nowMillis = nowMillis,
            onPick = { id -> viewModel.updateFormatter { it.copy(id = id) } },
            onDone = close,
        )
        SettingsPanel.Stream.CopyFrom -> {
            val spec = copyFromSpec(profiles, activeProfileId, viewModel::copyFrom)
            // The last other profile was deleted while the list was open: nothing to pick, so go back to the row.
            if (spec.options.isEmpty()) LaunchedEffect(Unit) { close() } else ChoicePanel(spec, onDone = close)
        }
    }
}

/** The profiles a copy can come from: every one but the active profile, in profile order. */
internal fun copyFromSpec(profiles: List<ProfileEntity>, activeProfileId: Long?, copy: (ProfileEntity) -> Unit): ChoiceSpec =
    ChoiceSpec(
        title = COPY_STREAM_SETTINGS_TITLE,
        options = profiles.filter { it.id != activeProfileId }.map { profile ->
            ChoiceOption(label = profile.name, selected = false, key = profile.id) { copy(profile) }
        },
    )
