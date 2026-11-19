package com.seastreams.tv.ui.screens.playback

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.seastreams.tv.domain.model.StreamSource

/**
 * Stream selection modal: a [StreamListPanel] with one addon card per enabled stream addon (in addon
 * order) over the selected addon's streams, opening on the `LAST USED` stream's addon and card when
 * listed. Focus stays inside the panel; `Back` closes it.
 */
@Composable
fun StreamPickerModal(state: StreamPickerState, onChoose: (StreamSource) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    StreamListPanel(
        title = state.title,
        subtitle = state.subtitle,
        loading = state.loading,
        streams = state.streams,
        emptyText = "No streams found. Press OK to close.",
        onEmptyClick = onDismiss,
        onChoose = { _, stream -> onChoose(stream) },
        failedAddons = state.failedAddons,
        lastUsedIndex = state.lastUsedIndex,
        addons = state.streamAddons,
    )
}
