package com.seastreams.tv.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.core.database.entity.LibrarySortOrder
import com.seastreams.tv.domain.model.Genre
import com.seastreams.tv.domain.usecase.LibraryQuery
import com.seastreams.tv.domain.usecase.label
import com.seastreams.tv.ui.components.TvDropdown
import com.seastreams.tv.ui.theme.OutlineDim
import com.seastreams.tv.ui.theme.TextPrimary
import com.seastreams.tv.ui.theme.focusPillColors
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.rememberListEdgeWrap
import com.seastreams.tv.ui.theme.verticalEdgeKeys
import com.seastreams.tv.ui.theme.verticalListEdgeWrap

/** Top controls: the persisted Sort selector and the Genre filter. */
@Composable
fun LibrarySortAndGenre(
    sort: LibrarySortOrder,
    onSort: (LibrarySortOrder) -> Unit,
    genres: List<Genre>,
    genreId: Int?,
    onGenre: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val genreChoices: List<Int?> = remember(genres) { listOf<Int?>(null) + genres.map { it.id } }
    val names = remember(genres) { genres.associate { it.id to it.name } }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TvDropdown(
            label = "Sort",
            options = LibrarySortOrder.entries,
            selected = sort,
            optionLabel = { it.label },
            onSelect = onSort,
        )
        TvDropdown(
            label = "Genre",
            options = genreChoices,
            selected = genreId?.takeIf { it in names },
            optionLabel = { id -> id?.let { names[it] } ?: ALL_GENRES },
            onSelect = onGenre,
        )
    }
}

/**
 * Vertical `#`, `A`–`Z` quick-jump strip on the right edge. Focusing a letter scrolls the grid to its
 * first title; OK moves focus onto that title. Letters with no titles are dimmed and do nothing.
 * Two-way §1 edge-stop-then-wrap: a fresh `Up` on `#` scrolls to and focuses `Z`, a fresh `Down` on
 * `Z` scrolls back to `#`; holding `Up`/`Down` stops on the edge letter and never leaves the bar.
 * `Left` never uses spatial search: it runs [onJump] with the letter's [leftTargets] entry
 * ([letterLeftTargets] of [letterIndex], precomputed off the main thread in [LibraryView]: the
 * letter's first title, else the nearest populated letter's), so focus lands on that exact poster. A fresh `Right`
 * on any letter runs [onWrapRight] (back to the filter sidebar), while a held `Right` stops on the
 * bar. [focus] is hoisted so the sidebar can wrap in.
 */
@Composable
fun LetterBar(
    letterIndex: Map<Char, Int>,
    leftTargets: List<Int?>,
    onPreview: (index: Int) -> Unit,
    onJump: (index: Int) -> Unit,
    focus: LetterBarFocus,
    onLetterFocused: (index: Int) -> Unit,
    onWrapRight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = focus.listState
    val first = focus.requesters.first()
    val last = focus.requesters.last()
    val letters = LibraryQuery.LETTERS
    val wrap = rememberListEdgeWrap(listState, first, last, letters.lastIndex)
    LazyColumn(
        state = listState,
        modifier = modifier.focusRestorer(first),
        contentPadding = PaddingValues(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(letters, key = { _, letter -> letter }) { i, letter ->
            val target = letterIndex[letter]
            val shape = RoundedCornerShape(6.dp)
            Surface(
                onClick = { target?.let(onJump) },
                modifier = Modifier
                    .size(LETTER_WIDTH, LETTER_HEIGHT)
                    .onFocusChanged {
                        if (it.isFocused) {
                            onLetterFocused(i)
                            target?.let(onPreview)
                        }
                    }
                    .focusRequester(focus.requesters[i])
                    // Fresh Left jumps once; held repeats are consumed here (no duplicate jumps, no spatial
                    // search). With no target the key passes through to ordinary focus search.
                    .then(
                        leftTargets.getOrNull(i)?.let { t -> Modifier.onDpadEdgeWrap(Key.DirectionLeft) { onJump(t) } }
                            ?: Modifier,
                    )
                    .verticalListEdgeWrap(i, letters.lastIndex, wrap.toFirst, wrap.toLast)
                    .onDpadEdgeWrap(Key.DirectionRight, onWrapRight),
                shape = ClickableSurfaceDefaults.shape(shape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
                colors = focusPillColors(contentColor = if (target != null) TextPrimary else OutlineDim),
            ) {
                Text(
                    letter.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/**
 * The edge keys letter [index] of the quick-jump bar ([letters], top to bottom) owns: `Up` on the
 * top-most letter (`#`), `Down` on the bottom-most (`Z`), both when the bar has a single letter.
 */
internal fun letterBarEdgeKeys(index: Int, letters: List<Char> = LibraryQuery.LETTERS): Set<Key> =
    verticalEdgeKeys(index, letters.lastIndex)

private const val ALL_GENRES = "All Genres"
val LETTER_WIDTH = 30.dp
private val LETTER_HEIGHT = 15.dp
