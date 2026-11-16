package com.seastreams.tv.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
import com.seastreams.tv.ui.theme.FocusPill
import com.seastreams.tv.ui.theme.OnFocusPill
import com.seastreams.tv.ui.theme.OutlineDim
import com.seastreams.tv.ui.theme.TextPrimary
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import kotlinx.coroutines.launch

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
    val genreChoices: List<Int?> = listOf<Int?>(null) + genres.map { it.id }
    val names = genres.associate { it.id to it.name }
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
 * Holding `Down` stops on `Z`; a fresh `Down` there wraps to `#`.
 */
@Composable
fun LetterBar(
    letterIndex: Map<Char, Int>,
    onPreview: (index: Int) -> Unit,
    onJump: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val letters = LibraryQuery.LETTERS
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
                    .onFocusChanged { if (it.isFocused) target?.let(onPreview) }
                    .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                    .then(
                        if (i == letters.lastIndex) {
                            Modifier.onDpadEdgeWrap(Key.DirectionDown) {
                                scope.launch {
                                    listState.scrollToItem(0)
                                    first.requestFocusAfterFrame()
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
                shape = ClickableSurfaceDefaults.shape(shape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = if (target != null) TextPrimary else OutlineDim,
                    focusedContainerColor = FocusPill,
                    focusedContentColor = OnFocusPill,
                ),
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

private const val ALL_GENRES = "All Genres"
val LETTER_WIDTH = 30.dp
private val LETTER_HEIGHT = 15.dp
