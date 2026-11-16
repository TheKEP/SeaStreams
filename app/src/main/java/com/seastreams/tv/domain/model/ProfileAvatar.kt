package com.seastreams.tv.domain.model

/**
 * Profile avatars, shared by the TV UI and the Web Portal so both render the same tile.
 * Persisted by ordinal (`ProfileEntity.avatarIndex`), so only append new entries.
 */
enum class ProfileAvatar(val glyph: String, val argb: Long) {
    MONSTER("👾", 0xFF2EA8E6),
    CAR("🚗", 0xFF3D4B66),
    BIRD("🐦", 0xFF8A5A1E),
    CAT("🐱", 0xFF7B4FD6),
    ROCKET("🚀", 0xFF1F9E7A),
    FOX("🦊", 0xFFD9622B),
    PANDA("🐼", 0xFF5C6577),
    OCTOPUS("🐙", 0xFFC2416B),
    ;

    /** `#RRGGBB` for CSS. */
    val cssColor: String get() = "#%06X".format(argb and 0xFFFFFF)

    companion object {
        fun at(index: Int): ProfileAvatar = entries.getOrElse(index) { MONSTER }
    }
}
