package com.seastreams.tv.ui.theme

/**
 * A LazyColumn model shared by [ReorderScrollTest] and the player card focus tests. Offsets are relative to the content
 * start, like `LazyListItemInfo`.
 */
internal class ModelList(
    keys: List<String>,
    private val rowSize: Int = 152,
    private val spacing: Int = 21,
    private val viewport: Int = 900,
    private val padBefore: Int = 10,
    private val padAfter: Int = 105,
) : RevealList {
    var keys: List<String> = keys
        private set
    private var measuredKeys = keys
    private var scroll = 0
    val contentEnd get() = viewport - padBefore - padAfter
    private val stride get() = rowSize + spacing

    override val layout: ListLayout
        get() = ListLayout(
            items = measuredKeys.indices.mapNotNull { i ->
                val top = i * stride - scroll
                if (top + rowSize <= -padBefore || top >= contentEnd + padAfter) null else ItemSpan(i, top, rowSize, measuredKeys[i])
            },
            contentStart = 0,
            contentEnd = contentEnd,
        )

    override suspend fun scrollBy(pixels: Float) {
        scroll = (scroll + pixels.toInt()).coerceIn(0, maxScroll())
    }

    override suspend fun scrollToItem(index: Int) {
        scroll = (index * stride).coerceIn(0, maxScroll())
    }

    private fun maxScroll() = (keys.size * stride - spacing - contentEnd).coerceAtLeast(0)

    /** A frame: the saved order gets measured, keeping the first visible row in place (LazyList's key anchoring). */
    private fun frame() {
        if (measuredKeys == keys) return
        val first = measuredKeys.indices.first { it * stride + rowSize > scroll }
        val within = scroll - first * stride
        val anchor = measuredKeys[first]
        measuredKeys = keys
        scroll = (keys.indexOf(anchor) * stride + within).coerceIn(0, maxScroll())
    }

    fun span(index: Int): ItemSpan? = layout.items.firstOrNull { it.index == index }

    fun fullyVisible(index: Int) = isFullyVisible(index, layout)

    /** Presses Move up / Move down on [key]: saves the new order, follows it, and returns the focused button. */
    suspend fun press(key: String, offset: Int): MoveButton? {
        val from = keys.indexOf(key)
        val to = (from + offset).coerceIn(0, keys.lastIndex)
        keys = keys.toMutableList().apply { add(to, removeAt(from)) }
        var focused: MoveButton? = null
        followMove(this, PendingMove(key, offset, to), keys, to, key, awaitFrame = ::frame) { button ->
            focused = button
            span(to) != null
        }
        return focused
    }
}
