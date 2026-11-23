package com.seastreams.tv.data.usenet.newznab

import java.util.Locale

/**
 * Whether a release title is the movie or episode searched for (PRD §12):
 * - movies: a year in the title must be within ±1 of the movie's; episode-tagged releases are rejected;
 * - episodes: an `SxxEyy` / `SxxEyy-Ezz` / `1x02` tag must name this season and episode, and season packs
 *   (`S01` alone, `Complete`) are rejected, since one stream must be one episode;
 * - [strict] (text `q=` results, which indexers match loosely) also requires the title's words to open the
 *   release name and a tag (or year) to be present.
 */
object ReleaseMatch {
    private val YEAR = Regex("(?<![0-9])(19\\d{2}|20\\d{2})(?![0-9])")
    // Applied to normalize()d text, where separators are already single spaces.
    private val EPISODE = Regex("(?<![a-z0-9])s(\\d{1,2}) ?e(\\d{1,3})((?: ?-? ?e\\d{1,3}|-\\d{1,3})*)(?![0-9])")
    private val EXTRA_EPISODE = Regex("(\\d{1,3})")
    private val CROSS = Regex("(?<![a-z0-9])(\\d{1,2})x(\\d{2,3})(?![0-9])")
    private val SEASON_ONLY = Regex("(?<![a-z0-9])(s\\d{1,2}(?![0-9e])|season ?\\d{1,2}(?![0-9])|complete)(?![a-z])")

    fun episodeTag(season: Int, episode: Int): String = "S%02dE%02d".format(Locale.ROOT, season, episode)

    fun matches(release: String, target: UsenetSearchTarget, strict: Boolean): Boolean {
        val name = normalize(release)
        if (strict && !titleOpens(name, target.title)) return false
        return when (target) {
            is UsenetSearchTarget.Movie -> movie(name, target.year, strict)
            is UsenetSearchTarget.Episode -> episode(name, target.season, target.episode, strict)
        }
    }

    private fun movie(name: String, year: Int?, strict: Boolean): Boolean {
        if (EPISODE.containsMatchIn(name) || CROSS.containsMatchIn(name)) return false
        val years = YEAR.findAll(name).map { it.value.toInt() }.toList()
        if (year == null) return !strict || years.isNotEmpty()
        if (years.isEmpty()) return !strict
        return years.any { kotlin.math.abs(it - year) <= 1 }
    }

    private fun episode(name: String, season: Int, episode: Int, strict: Boolean): Boolean {
        EPISODE.find(name)?.let { match ->
            if (match.groupValues[1].toInt() != season) return false
            val first = match.groupValues[2].toInt()
            val more = EXTRA_EPISODE.findAll(match.groupValues[3]).map { it.value.toInt() }.toList()
            val last = more.lastOrNull()?.takeIf { it >= first } ?: first
            return episode in first..last
        }
        CROSS.find(name)?.let { match ->
            return match.groupValues[1].toInt() == season && match.groupValues[2].toInt() == episode
        }
        if (SEASON_ONLY.containsMatchIn(name)) return false
        return !strict
    }

    private fun titleOpens(name: String, title: String?): Boolean {
        val words = title?.let(::normalize)?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
        if (words.isEmpty()) return true
        val release = name.split(' ').filter { it.isNotEmpty() }
        val skipArticle = if (release.firstOrNull() == "the" && words.firstOrNull() != "the") release.drop(1) else release
        val wanted = if (words.firstOrNull() == "the" && skipArticle.firstOrNull() != "the") words.drop(1) else words
        return skipArticle.size >= wanted.size && wanted.indices.all { skipArticle[it] == wanted[it] }
    }

    /** Lower case; `.`, `_`, brackets and other separators become single spaces; `&` → `and`. */
    internal fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace("&", " and ").replace("'", "")
            .replace(Regex("[^\\p{L}\\p{N}-]+"), " ").trim()
}
