package com.seastreams.tv.data.debrid.library

import com.seastreams.tv.data.debrid.api.DebridFile
import com.seastreams.tv.data.debrid.api.isVideoFileName
import java.util.Locale

/**
 * Which files of a library item can be played, and which of them is a given episode (PRD §14 *Matching*). A port of
 * Nabrio `EpisodeFilename` (`videos` / `choices` / `episodes`): video extensions only, `sample` / `proof` /
 * featurette / trailer files out, `SxxEyy` with `-Ezz` / `E01E02` / `to` ranges and `1x02` tags. Added here: files
 * under 5% of the item's largest video are dropped, and inside a season folder (`Season 2`, `S02`) a file named only by
 * its episode (`E05`, `Episode 5`, `05 - Title`) counts as that season's episode.
 */
object EpisodeFilePicker {

    /** The playable videos of one item: video extension, not a sample/extra, at least 5% of the largest video. */
    fun playable(files: List<DebridFile>): List<DebridFile> {
        val videos = files.filter { isVideoFileName(it.name) && !SAMPLE.containsMatchIn("/" + it.path) }
        val largest = videos.maxOfOrNull { it.sizeBytes } ?: return emptyList()
        if (largest <= 0) return videos
        return videos.filter { it.sizeBytes <= 0 || it.sizeBytes * MIN_SHARE_DIVISOR >= largest }
    }

    /**
     * The files of [files] (already [playable]) that are season [season] episode [episode]. [itemIsEpisode] says the
     * item itself names that episode, so its single video counts even when the file name carries no tag.
     * [itemSeason] is the season a pack item names, used for files named only by episode.
     */
    fun pick(files: List<DebridFile>, season: Int, episode: Int, itemIsEpisode: Boolean, itemSeason: Int?): List<DebridFile> {
        val wanted = season to episode
        val tagged = files.filter { wanted in episodes(it.name) }
        if (tagged.isNotEmpty()) return tagged
        val untagged = files.filter { episodes(it.name).isEmpty() }
        val byFolder = untagged.filter { file ->
            val folderSeason = seasonOfFolders(file.path) ?: itemSeason
            folderSeason == season && bareEpisode(file.name) == episode
        }
        if (byFolder.isNotEmpty()) return byFolder
        if (itemIsEpisode && files.size == 1 && untagged.size == 1) return untagged
        return emptyList()
    }

    /** Every (season, episode) a name tags: `S01E02`, `S01E01-E03`, `S01E01E02`, `S01E01 to 03`, `1x02`, `1x01-03`. */
    fun episodes(name: String): Set<Pair<Int, Int>> {
        val found = LinkedHashSet<Pair<Int, Int>>()
        SEASON_EPISODE.findAll(name).forEach { match ->
            val season = match.groupValues[1].toInt()
            var previous = match.groupValues[2].toInt()
            found += season to previous
            TAIL_EPISODE.findAll(match.groupValues[3]).forEach { tail ->
                val rangeEnd = tail.groupValues[1].toIntOrNull()
                if (rangeEnd != null) {
                    // Bounded, so a malformed tag can't claim every episode.
                    if (rangeEnd >= previous && rangeEnd - previous <= MAX_RANGE) for (e in previous..rangeEnd) found += season to e
                    previous = rangeEnd
                } else {
                    previous = tail.groupValues[2].toInt()
                    found += season to previous
                }
            }
        }
        CROSS_EPISODE.findAll(name).forEach { match ->
            val season = match.groupValues[1].toInt()
            val start = match.groupValues[2].toInt()
            val end = match.groupValues[3].toIntOrNull() ?: start
            if (end >= start && end - start <= MAX_RANGE) for (e in start..end) found += season to e
        }
        return found
    }

    /** The season a folder of [path] names (`Season 2`, `S02`, `Staffel 2`), innermost folder first. */
    internal fun seasonOfFolders(path: String): Int? =
        path.split('/').dropLast(1).asReversed().firstNotNullOfOrNull { folder -> seasonOf(folder) }

    /** The single season a name names without an episode, or `null`. */
    internal fun seasonOf(name: String): Int? {
        if (episodes(name).isNotEmpty()) return null
        return SEASON.find(name)?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() }
    }

    /** An episode number on its own: `E05`, `Ep 5`, `Episode 05`, or a leading `05 - Title` / `05.`. */
    internal fun bareEpisode(name: String): Int? {
        val base = name.substringBeforeLast('.').lowercase(Locale.ROOT)
        BARE_EPISODE.find(base)?.let { return it.groupValues[1].toInt() }
        return LEADING_NUMBER.find(base)?.groupValues?.get(1)?.toInt()
    }

    private const val MIN_SHARE_DIVISOR = 20L // 5%
    private const val MAX_RANGE = 100
    private val SAMPLE = Regex(
        "(?i)(?:^|[/\\\\ ._-])(?:sample|featurette|proof)(?:[/\\\\ ._-]|$)|(?:^|[/\\\\])(?:extras|trailers|featurettes)[/\\\\]|[ ._-]trailer\\.[a-z0-9]+$",
    )
    private val SEASON_EPISODE =
        Regex("(?i)(?:^|[^a-z0-9])s(\\d{1,2})[ ._-]*e(\\d{1,3})((?:(?:[ ._-]*(?:-|to)[ ._-]*e?\\d{1,3})|(?:[ ._-]*e\\d{1,3}))*)")
    private val CROSS_EPISODE = Regex("(?i)(?:^|[^a-z0-9])(\\d{1,2})x(\\d{1,3})(?:[ ._-]*-[ ._-]*(\\d{1,3}))?(?=$|[^0-9])")
    private val TAIL_EPISODE = Regex("(?i)(?:[ ._-]*(?:-|to)[ ._-]*e?(\\d{1,3}))|(?:[ ._-]*e(\\d{1,3}))")
    private val SEASON = Regex("(?i)(?<![a-z0-9])(?:s(\\d{1,2})(?![0-9e])|(?:season|staffel|saison|temporada)[ ._-]?(\\d{1,2})(?![0-9]))")
    private val BARE_EPISODE = Regex("(?<![a-z0-9])(?:e|ep|episode)[ ._-]?(\\d{1,3})(?![0-9])")
    private val LEADING_NUMBER = Regex("^(\\d{1,3})(?=[ ._-])")
}
