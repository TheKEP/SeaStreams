package com.seastreams.tv.domain.model

/** A TMDB genre (ids are shared by `/discover?with_genres=` and `genre_ids` on list results). */
data class Genre(val id: Int, val name: String)
