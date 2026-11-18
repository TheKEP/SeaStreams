package com.seastreams.tv.data.details

import com.seastreams.tv.core.network.TmdbCastMember
import com.seastreams.tv.core.network.TmdbCreator
import com.seastreams.tv.core.network.TmdbCrewMember
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.domain.model.CreditPerson
import com.seastreams.tv.domain.model.TitleDetails

/**
 * People for the Details cast grid (PRD §6), built from the `credits` already appended to the title's
 * details request — never an extra TMDB call.
 *
 * Slot 1 is the first crew `Director`, or — when there is none (typical for shows) — the first of
 * [creators]. Then the top [TitleDetails.MAX_CAST] cast by billing `order`, de-duplicated by person id
 * so a director who also acts, or an actor billed twice, appears once and the next billed actor fills
 * the slot.
 */
internal fun topCredits(
    cast: List<TmdbCastMember>,
    crew: List<TmdbCrewMember>,
    creators: List<TmdbCreator>,
    imageUrls: TmdbImageUrls,
): List<CreditPerson> {
    val director = crew.firstOrNull { it.job == DIRECTOR_JOB && it.name.isNotBlank() }
        ?.let { CreditPerson(it.id, it.name.trim(), DIRECTOR_JOB, imageUrls.profile(it.profilePath), isDirector = true) }
        ?: creators.firstOrNull { it.name.isNotBlank() }
            ?.let { CreditPerson(it.id, it.name.trim(), CREATOR_ROLE, imageUrls.profile(it.profilePath), isDirector = true) }

    val seen = mutableSetOf<Int>()
    director?.let { seen += it.id }
    val billed = cast.asSequence()
        .sortedBy { it.order }
        .filter { it.name.isNotBlank() && seen.add(it.id) }
        .take(TitleDetails.MAX_CAST)
        .map { member ->
            CreditPerson(
                id = member.id,
                name = member.name.trim(),
                role = member.character?.trim()?.takeIf { it.isNotEmpty() },
                profileUrl = imageUrls.profile(member.profilePath),
                isDirector = false,
            )
        }
        .toList()
    return listOfNotNull(director) + billed
}

private const val DIRECTOR_JOB = "Director"
private const val CREATOR_ROLE = "Creator"
