package com.seastreams.tv.core.util

/** Exact-name enum lookup that returns `null` (instead of throwing like `enumValueOf`) for unknown names. */
inline fun <reified E : Enum<E>> enumByNameOrNull(name: String?): E? =
    enumValues<E>().firstOrNull { it.name == name }
