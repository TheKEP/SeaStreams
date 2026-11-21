package com.seastreams.tv.ui.screens.library

import androidx.compose.ui.input.key.Key
import com.seastreams.tv.domain.usecase.LibraryQuery
import com.seastreams.tv.ui.theme.EdgeKeyResult
import com.seastreams.tv.ui.theme.edgeKeyResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Two-way §1 edge-stop-then-wrap on the Library A–Z quick-jump bar (`#` ⇄ `Z`). */
class LetterBarEdgeWrapTest {

    private val letters = LibraryQuery.LETTERS
    private val top = letters.indexOf('#')
    private val bottom = letters.indexOf('Z')

    @Test
    fun `the bar runs from # at the top to Z at the bottom`() {
        assertEquals(0, top)
        assertEquals(letters.lastIndex, bottom)
    }

    @Test
    fun `# owns Up and Z owns Down`() {
        assertEquals(setOf(Key.DirectionUp), letterBarEdgeKeys(top))
        assertEquals(setOf(Key.DirectionDown), letterBarEdgeKeys(bottom))
    }

    @Test
    fun `A to Y own no edge key`() {
        ('A'..'Y').forEach { assertEquals(emptySet<Key>(), letterBarEdgeKeys(letters.indexOf(it)), "$it") }
    }

    @Test
    fun `no letter treats Left as a wrap key, so Left always jumps into the grid`() {
        letters.indices.forEach { assertEquals(false, Key.DirectionLeft in letterBarEdgeKeys(it), "${letters[it]}") }
    }

    @Test
    fun `fresh Left on a letter jumps once, held Left is consumed without a duplicate jump`() {
        // Letters with a Left target carry onDpadEdgeWrap(Left) { onJump(target) }.
        assertEquals(EdgeKeyResult.WRAP, edgeKeyResult(matchesKey = true, isKeyDown = true, repeatCount = 0))
        listOf(1, 3, 20).forEach {
            assertEquals(EdgeKeyResult.HOLD, edgeKeyResult(matchesKey = true, isKeyDown = true, repeatCount = it))
        }
        assertEquals(EdgeKeyResult.PASS, edgeKeyResult(matchesKey = true, isKeyDown = false, repeatCount = 0))
    }

    @Test
    fun `fresh Up on # wraps to Z, held Up stops on #`() {
        val owns = Key.DirectionUp in letterBarEdgeKeys(top)
        assertEquals(EdgeKeyResult.WRAP, edgeKeyResult(matchesKey = owns, isKeyDown = true, repeatCount = 0))
        assertEquals(EdgeKeyResult.HOLD, edgeKeyResult(matchesKey = owns, isKeyDown = true, repeatCount = 4))
    }

    @Test
    fun `fresh Down on Z wraps to #, held Down stops on Z`() {
        val owns = Key.DirectionDown in letterBarEdgeKeys(bottom)
        assertEquals(EdgeKeyResult.WRAP, edgeKeyResult(matchesKey = owns, isKeyDown = true, repeatCount = 0))
        assertEquals(EdgeKeyResult.HOLD, edgeKeyResult(matchesKey = owns, isKeyDown = true, repeatCount = 1))
    }

    @Test
    fun `inward presses on an edge letter are ordinary focus moves`() {
        val downOnTop = Key.DirectionDown in letterBarEdgeKeys(top)
        val upOnBottom = Key.DirectionUp in letterBarEdgeKeys(bottom)
        assertEquals(EdgeKeyResult.PASS, edgeKeyResult(matchesKey = downOnTop, isKeyDown = true, repeatCount = 0))
        assertEquals(EdgeKeyResult.PASS, edgeKeyResult(matchesKey = upOnBottom, isKeyDown = true, repeatCount = 0))
    }

    @Test
    fun `a single-letter bar consumes both Up and Down so focus stays put`() {
        val keys = letterBarEdgeKeys(index = 0, letters = listOf('#'))
        assertEquals(setOf(Key.DirectionUp, Key.DirectionDown), keys)
        listOf(0, 2).forEach { repeat ->
            assertEquals(true, edgeKeyResult(Key.DirectionUp in keys, true, repeat) != EdgeKeyResult.PASS)
            assertEquals(true, edgeKeyResult(Key.DirectionDown in keys, true, repeat) != EdgeKeyResult.PASS)
        }
    }
}
