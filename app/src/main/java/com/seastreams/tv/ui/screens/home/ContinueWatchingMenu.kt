package com.seastreams.tv.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.ui.components.ActionMenuRequest
import com.seastreams.tv.ui.components.MenuAction
import com.seastreams.tv.ui.components.MetadataFormat
import kotlinx.coroutines.launch

const val CONTINUE_WATCHING_VIEW_DETAILS = "View details"
const val CONTINUE_WATCHING_REMOVE = "Remove"
const val CONTINUE_WATCHING_CLEAR_PROGRESS = "Clear progress"

/**
 * Continue Watching card long-press menu (PRD §3): `View details`, `Remove`, `Clear progress`.
 * [returnFocus] is the row's active-card requester, which sits on the long-pressed card, so `Back`
 * lands exactly where the menu was opened. `View details` always opens Details, whatever *Auto-play
 * from Continue Watching* is set to.
 */
fun continueWatchingMenu(
    item: ContinueWatchingItem,
    returnFocus: FocusRequester,
    onViewDetails: (HomeDestination.Details) -> Unit,
    onRemove: () -> Unit,
    onClearProgress: () -> Unit,
): ActionMenuRequest = ActionMenuRequest(
    title = item.media.title,
    subtitle = MetadataFormat.episodeBadge(item.seasonNumber, item.episodeNumber),
    actions = listOf(
        MenuAction(CONTINUE_WATCHING_VIEW_DETAILS) { onViewDetails(HomeDestination.Details(item.media)) },
        MenuAction(CONTINUE_WATCHING_REMOVE, onRemove),
        MenuAction(CONTINUE_WATCHING_CLEAR_PROGRESS, onClearProgress),
    ),
    returnFocus = returnFocus,
)

/** One Continue Watching card: a movie, or one episode of a show (several can share a media key). */
data class ContinueWatchingKey(val mediaKey: String, val seasonNumber: Int, val episodeNumber: Int)

val ContinueWatchingItem.continueWatchingKey: ContinueWatchingKey
    get() = ContinueWatchingKey(media.key, seasonNumber, episodeNumber)

/** A card the user chose `Remove` / `Clear progress` on, at [index] of the row when it was chosen. */
data class ContinueWatchingRemoval(val key: ContinueWatchingKey, val index: Int)

/** Where D-pad focus goes once a removed Continue Watching card has left the row. */
sealed interface RemovalFocus {
    /** The card now at [index] of the Continue Watching row. */
    data class Card(val index: Int) : RemovalFocus

    /** The row was emptied: the first card-row below it (by [sectionKey]). */
    data class RowBelow(val sectionKey: String) : RemovalFocus

    /** The row was emptied and nothing focusable is below: the hero (else the `Home` pill). */
    data object Top : RemovalFocus
}

/**
 * Focus index after removing the card at [removedIndex], with [remainingCount] cards left: the card
 * that shifted in from the right (same index), or the new last card when the rightmost was removed.
 */
fun indexAfterRemoval(removedIndex: Int, remainingCount: Int): Int =
    removedIndex.coerceAtMost(remainingCount - 1).coerceAtLeast(0)

/** `null` while [removal]'s card is still on the row (the database hasn't caught up yet). */
fun focusAfterRemoval(removal: ContinueWatchingRemoval, sections: List<HomeSection>): RemovalFocus? {
    val remaining = sections.firstNotNullOfOrNull { it as? HomeSection.ContinueWatching }?.items.orEmpty()
    if (remaining.any { it.continueWatchingKey == removal.key }) return null
    if (remaining.isNotEmpty()) return RemovalFocus.Card(indexAfterRemoval(removal.index, remaining.size))
    val below = sections.firstOrNull { it !is HomeSection.Hero }?.takeIf { it.focusable }
    return below?.let { RemovalFocus.RowBelow(it.key) } ?: RemovalFocus.Top
}

/** Lazily created active-card requesters for Home's rows, keyed by [HomeSection.key]. */
class SectionCardRequesters {
    private val requesters = mutableMapOf<String, FocusRequester>()

    fun of(sectionKey: String): FocusRequester = requesters.getOrPut(sectionKey) { FocusRequester() }
}

/**
 * Once [removal]'s card has left [sections], resolves where focus goes ([focusAfterRemoval]), hands it
 * to [moveFocus] and calls [onDone]. Keyed on the sections, so it re-checks every row update. The move
 * runs outside the effect so clearing the removal in [onDone] can't cancel it.
 */
@Composable
fun ContinueWatchingRemovalFocusEffect(
    removal: ContinueWatchingRemoval?,
    sections: List<HomeSection>,
    moveFocus: suspend (RemovalFocus) -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(removal, sections) {
        val pending = removal ?: return@LaunchedEffect
        val target = focusAfterRemoval(pending, sections) ?: return@LaunchedEffect
        scope.launch { moveFocus(target) }
        onDone()
    }
}
