package com.seastreams.tv.ui.screens.settings

/** A control of one Manage addons row that can take focus after a removal. */
enum class AddonRowControl { REMOVE, TOGGLE }

/** Where D-pad focus goes when an addon is removed from the Manage addons sub-panel (PRD §10). */
sealed interface AddonRemovalFocus {
    /** The addon at [index] in the list *before* the removal, i.e. the one directly below it. */
    data class Row(val index: Int, val control: AddonRowControl) : AddonRemovalFocus

    /** The closing `Install more addons` row: the removed addon was the last one. */
    data object InstallMore : AddonRemovalFocus
}

/**
 * Focus target for removing the addon at [removedIndex] of [addonCount]. It is resolved and focused
 * *before* the row disappears, so focus is never on a disposed control: the next addon down (it
 * slides up into [removedIndex]) on its `Remove` button, keeping the column for repeated removals,
 * or on its toggle when it is a built-in without `Remove` ([isRemovable] by pre-removal index);
 * `Install more addons` when nothing is below.
 */
fun addonRemovalFocus(removedIndex: Int, addonCount: Int, isRemovable: (Int) -> Boolean): AddonRemovalFocus {
    val next = removedIndex + 1
    if (next >= addonCount) return AddonRemovalFocus.InstallMore
    return AddonRemovalFocus.Row(next, if (isRemovable(next)) AddonRowControl.REMOVE else AddonRowControl.TOGGLE)
}
