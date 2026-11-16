package com.seastreams.tv.ui.screens.home

import com.seastreams.tv.domain.model.RowContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Longest the profile → Home transition waits for content before revealing Home anyway. */
const val REVEAL_TIMEOUT_MILLIS = 1_200L

/** Crossfade from the loading badge into Home once it is revealed. */
const val REVEAL_CROSSFADE_MILLIS = 250

/** The hero and the first catalog row have both settled (loaded or failed). */
fun HomeUiState.isFirstScreenReady(): Boolean =
    hero !is HeroState.Loading && rows.firstOrNull()?.let { it.content !is RowContent.Loading } == true

/** Hero art plus the first posters in the order Home draws them (Continue Watching first). */
fun HomeUiState.firstScreenImages(): List<ImageTarget> = firstScreenImages(
    hero = (hero as? HeroState.Loaded)?.items,
    rows = listOf(continueWatching.map { it.media }) +
        rows.mapNotNull { (it.content as? RowContent.Loaded)?.row?.items },
)

/**
 * Waits until [profileId]'s Home has its first screen of content and those images are decoded
 * ([warm]), but never longer than [timeoutMillis] so a slow API cannot block entry.
 * Returns early (without warming) if the profile turns out to be deleted.
 *
 * @return `true` when everything was ready in time, `false` when the timeout cut it short.
 */
suspend fun awaitHomeReveal(
    states: Flow<HomeUiState>,
    profileId: Long,
    warm: suspend (List<ImageTarget>) -> Unit,
    timeoutMillis: Long = REVEAL_TIMEOUT_MILLIS,
): Boolean = withTimeoutOrNull(timeoutMillis) {
    val settled = states.first {
        it.missingProfileId == profileId || (it.profile?.id == profileId && it.isFirstScreenReady())
    }
    if (settled.missingProfileId != profileId) warm(settled.firstScreenImages())
    true
} ?: false
