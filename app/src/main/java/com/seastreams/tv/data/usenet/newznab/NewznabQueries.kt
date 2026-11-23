package com.seastreams.tv.data.usenet.newznab

/** What a Usenet search is for: a movie, or one episode of a show. */
sealed interface UsenetSearchTarget {
    val imdbId: String?
    val title: String?

    data class Movie(override val imdbId: String?, override val title: String?, val year: Int?) : UsenetSearchTarget

    data class Episode(
        override val imdbId: String?,
        override val title: String?,
        val season: Int,
        val episode: Int,
    ) : UsenetSearchTarget
}

/**
 * One Newznab request without the API key (added at send time). [byId] queries are matched to the title by the
 * indexer; text (`q=`) queries are checked by [ReleaseMatch] strictly.
 */
data class NewznabQuery(val params: List<Pair<String, String>>, val byId: Boolean) {
    fun param(name: String): String? = params.firstOrNull { it.first == name }?.second
}

/**
 * Builds the searches for one title from the indexer's caps (PRD §12): `t=movie&imdbid` /
 * `t=tvsearch&imdbid&season&ep` when the indexer supports them, then a `q=` fallback, which runs only when the id
 * search is unsupported or returns nothing. Movies search `cat=2000`, shows `cat=5000`; every query asks for
 * `extended=1` so the `newznab:attr` size / password / imdb attributes come back.
 */
object NewznabQueries {
    const val MOVIE_CATEGORY = "2000"
    const val TV_CATEGORY = "5000"
    const val LIMIT = "100"

    fun plan(caps: NewznabCaps, target: UsenetSearchTarget): List<NewznabQuery> {
        val imdb = target.imdbId?.removePrefix("tt")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        val title = target.title?.let(::cleanTitle)?.takeIf { it.isNotEmpty() }
        return when (target) {
            is UsenetSearchTarget.Movie -> listOfNotNull(
                imdb?.takeIf { caps.movieSearch.supports("imdbid") }?.let { query(true, "movie", MOVIE_CATEGORY, "imdbid" to it) },
                title?.let {
                    val text = listOfNotNull(it, target.year?.toString()).joinToString(" ")
                    val mode = if (caps.movieSearch.supports("q")) "movie" else "search"
                    query(false, mode, MOVIE_CATEGORY, "q" to text)
                },
            )
            is UsenetSearchTarget.Episode -> {
                val season = target.season.toString()
                val episode = target.episode.toString()
                val tvText = caps.tvSearch.supports("q") && caps.tvSearch.supports("season") && caps.tvSearch.supports("ep")
                listOfNotNull(
                    imdb?.takeIf { caps.tvSearch.supports("imdbid") }
                        ?.let { query(true, "tvsearch", TV_CATEGORY, "imdbid" to it, "season" to season, "ep" to episode) },
                    title?.let {
                        if (tvText) {
                            query(false, "tvsearch", TV_CATEGORY, "q" to it, "season" to season, "ep" to episode)
                        } else {
                            query(false, "search", TV_CATEGORY, "q" to "$it ${ReleaseMatch.episodeTag(target.season, target.episode)}")
                        }
                    },
                )
            }
        }
    }

    /** `t=caps` (cached 14 days per indexer). */
    val CAPS = NewznabQuery(listOf("t" to "caps"), byId = false)

    private fun query(byId: Boolean, mode: String, category: String, vararg extra: Pair<String, String>) =
        NewznabQuery(listOf("t" to mode) + extra + listOf("cat" to category, "extended" to "1", "limit" to LIMIT), byId)

    /** Punctuation indexers trip over (`:`, `'`, `&`) becomes spaces; the words stay. */
    private fun cleanTitle(title: String): String =
        title.replace('&', ' ').replace(Regex("[^\\p{L}\\p{N} .-]"), " ").replace(Regex("\\s+"), " ").trim()
}
