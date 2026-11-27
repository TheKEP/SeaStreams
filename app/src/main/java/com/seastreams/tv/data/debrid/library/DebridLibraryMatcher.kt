package com.seastreams.tv.data.debrid.library

import com.seastreams.tv.data.debrid.api.DebridFile
import com.seastreams.tv.data.debrid.api.DebridItem
import com.seastreams.tv.data.usenet.newznab.ReleaseMatch
import com.seastreams.tv.data.usenet.newznab.UsenetSearchTarget
import com.seastreams.tv.domain.streams.parse.ReleaseParser
import java.text.Normalizer
import java.util.Locale

/** What a Debrid library lookup is for: the same movie / episode shape the Usenet search uses. */
typealias DebridSearchTarget = UsenetSearchTarget

/** A library item that may hold the title, and how strongly its name says so. */
data class RankedItem(val item: DebridItem, val score: Int, val season: Int?)

/**
 * Matches library items and their files against the movie or episode being resolved (PRD §14 *Matching*), pure.
 * Every folder level of an item's name is read with [ReleaseParser]:
 * - an IMDb tag (`tt1234567`) equal to the title's id matches outright;
 * - movies: the normalised title, and a year within ±1 when the name states one ([ReleaseMatch]); episode-tagged
 *   names never match a movie;
 * - episodes: the normalised show title plus the episode's `SxxEyy` / `1x02` / range tag, a season pack of its
 *   season, or a show folder without any tag; a name tagging another episode or season never matches.
 * [rank] orders the items; [pick] then chooses the files inside one ([EpisodeFilePicker] for episodes).
 */
object DebridLibraryMatcher {

    /** Items that may hold [target], strongest first (listing order kept within a score). */
    fun rank(items: List<DebridItem>, target: DebridSearchTarget): List<RankedItem> =
        items.mapNotNull { item -> score(item.name, target)?.let { (score, season) -> RankedItem(item, score, season) } }
            .sortedByDescending { it.score }

    /** The files of [ranked] that are [target], biggest first. */
    fun pick(ranked: RankedItem, files: List<DebridFile>, target: DebridSearchTarget): List<DebridFile> {
        val playable = EpisodeFilePicker.playable(files)
        val chosen = when (target) {
            is UsenetSearchTarget.Movie -> movieFiles(playable, target)
            is UsenetSearchTarget.Episode -> EpisodeFilePicker.pick(
                playable, target.season, target.episode, itemIsEpisode = ranked.score == EPISODE, itemSeason = ranked.season,
            )
        }
        return chosen.sortedByDescending { it.sizeBytes }
    }

    /** Files that name the movie themselves, else the item's biggest video (a folder holds one film plus extras). */
    private fun movieFiles(files: List<DebridFile>, target: UsenetSearchTarget.Movie): List<DebridFile> {
        val named = files.filter { file -> movieScore(segment = file.name, target = target) != null && EpisodeFilePicker.episodes(file.name).isEmpty() }
        if (named.isNotEmpty()) return named
        val clean = files.filter { EpisodeFilePicker.episodes(it.name).isEmpty() }
        return listOfNotNull(clean.maxByOrNull { it.sizeBytes })
    }

    /** The best (score, pack season) over [name]'s folder levels, or `null` when none matches. */
    internal fun score(name: String, target: DebridSearchTarget): Pair<Int, Int?>? {
        val imdb = target.imdbId?.lowercase(Locale.ROOT)
        if (imdb != null && IMDB.findAll(name.lowercase(Locale.ROOT)).any { it.value == imdb }) {
            val season = (target as? UsenetSearchTarget.Episode)?.let { name.split('/').firstNotNullOfOrNull(EpisodeFilePicker::seasonOf) }
            return IMDB_MATCH to season
        }
        val segments = name.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        return when (target) {
            is UsenetSearchTarget.Movie -> segments.mapNotNull { movieScore(it, target) }.maxOrNull()?.let { it to null }
            is UsenetSearchTarget.Episode -> episodeScore(segments, target)
        }
    }

    private fun movieScore(segment: String, target: UsenetSearchTarget.Movie): Int? {
        val parsed = ReleaseParser.parse(segment)
        if (!sameTitle(titleOf(segment), target.title)) return null
        if (!ReleaseMatch.matches(segment, target, strict = false)) return null
        return if (parsed.year != null && target.year != null) TITLE_YEAR else TITLE_ONLY
    }

    /**
     * Episodes: the show's title must open some folder level, and no level may tag another season or episode. The
     * strongest level decides: the episode itself, a pack of its season, or the bare show.
     */
    private fun episodeScore(segments: List<String>, target: UsenetSearchTarget.Episode): Pair<Int, Int?>? {
        if (segments.none { sameTitle(titleOf(it), target.title) }) return null
        var best = SHOW
        var packSeason: Int? = null
        for (segment in segments) {
            val tags = EpisodeFilePicker.episodes(segment)
            if (tags.isNotEmpty()) {
                // A level named after another episode is not this one.
                if ((target.season to target.episode) !in tags) return null
                best = EPISODE
                continue
            }
            val seasons = ReleaseParser.parse(segment).seasons
            if (seasons.isNotEmpty()) {
                if (target.season !in seasons) return null
                if (best < SEASON_PACK) best = SEASON_PACK
                if (seasons.size == 1) packSeason = seasons.first()
            }
        }
        return best to (packSeason ?: target.season.takeIf { best == SEASON_PACK })
    }

    /**
     * [segment]'s title: [ReleaseParser]'s, read from the text before the first episode tag (the parser doesn't stop at
     * chained tags such as `S02E01E02`).
     */
    private fun titleOf(segment: String): String? {
        val tag = TAG_START.find(segment)?.range?.first?.takeIf { it > 0 }
        return ReleaseParser.parse(if (tag != null) segment.substring(0, tag) else segment).title
    }

    /** Titles compared without accents, case, punctuation, a leading `The`, or a trailing country code. */
    internal fun sameTitle(release: String?, wanted: String?): Boolean {
        val a = titleKey(release ?: return false)
        val b = titleKey(wanted ?: return false)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.withoutRegion() == b || b.withoutRegion() == a
    }

    private fun titleKey(text: String): String {
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
        return ReleaseMatch.normalize(plain).replace('-', ' ').replace(SPACES, " ").trim().removePrefix("the ").trim()
    }

    private fun String.withoutRegion(): String = REGION.replace(this, "").trim()

    private const val IMDB_MATCH = 100
    private const val EPISODE = 90
    private const val TITLE_YEAR = 80
    private const val SEASON_PACK = 70
    private const val TITLE_ONLY = 60
    private const val SHOW = 50
    private val TAG_START = Regex("(?i)(?<![a-z0-9])(?:s\\d{1,2}[ ._-]*e\\d{1,3}|\\d{1,2}x\\d{1,3}(?![0-9]))")
    private val IMDB = Regex("(?<![a-z0-9])tt\\d{7,8}(?![0-9])")
    private val MARKS = Regex("\\p{M}+")
    private val SPACES = Regex("\\s+")
    private val REGION = Regex(" (?:us|uk|au|nz|ca)$")
}
