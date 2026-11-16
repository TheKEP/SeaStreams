package com.seastreams.tv.core.util

import kotlinx.coroutines.CancellationException

/**
 * Runs [block] and returns its result, or `null` if it throws. Coroutine cancellation is always
 * rethrown, so a best-effort load never swallows the cancellation of its caller.
 */
inline fun <T> orNullUnlessCancelled(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
