package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.entity.AddonEntity

/**
 * One built-in stream source (`usenet://builtin`, `debrid://builtin`) as a profile has it (ARCHITECTURE §11): its
 * card name (the entry's custom name, else its own), and whether the profile switched it off (its row exists but is
 * disabled, so it isn't searched). A profile without a row yet (the entry is added in the background) still gets the
 * source, at the bottom.
 */
internal class BuiltInSlot(val url: String, val name: String, val disabled: Boolean) {

    companion object {
        /** [url]'s slot from the profile's [enabled] rows and whether a (disabled) row [exists] at all. */
        fun of(url: String, defaultName: String, enabled: List<AddonEntity>, exists: Boolean): BuiltInSlot {
            val row = enabled.firstOrNull { it.manifestUrl == url }
            return BuiltInSlot(url, row?.let { it.customName ?: it.name } ?: defaultName, disabled = row == null && exists)
        }
    }
}

/**
 * Every enabled addon URL in the profile's order, with each built-in source in [slots] (those that answered) that has
 * no row yet appended at the end, in [slots] order. Results, cards and the `Addon` sort criterion all follow it, so a built-in source's results sit
 * exactly where its entry sits.
 */
internal fun streamOrder(enabled: List<AddonEntity>, slots: List<BuiltInSlot>): List<String> {
    val urls = enabled.map { it.manifestUrl }
    return urls + slots.filter { !it.disabled && it.url !in urls }.map { it.url }
}
