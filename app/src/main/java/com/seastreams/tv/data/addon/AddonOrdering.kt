package com.seastreams.tv.data.addon

/** Pure list operations behind addon reordering, kept separate so they are trivially testable. */
object AddonOrdering {

    /**
     * Moves [url] by [offset] positions (negative = up), clamped to the list bounds.
     * Returns [order] unchanged when [url] is not present.
     */
    fun move(order: List<String>, url: String, offset: Int): List<String> {
        val from = order.indexOf(url)
        if (from < 0 || offset == 0) return order
        val to = (from + offset).coerceIn(0, order.lastIndex)
        if (to == from) return order
        return order.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * Reconciles a client-requested order with the addons that actually exist: unknown or duplicate
     * URLs are dropped, and existing addons the client omitted (e.g. installed concurrently) keep
     * their relative order at the end. The result is always a permutation of [current].
     */
    fun reconcile(current: List<String>, requested: List<String>): List<String> {
        val known = current.toSet()
        val head = requested.filter { it in known }.distinct()
        val headSet = head.toSet()
        return head + current.filterNot { it in headSet }
    }
}
