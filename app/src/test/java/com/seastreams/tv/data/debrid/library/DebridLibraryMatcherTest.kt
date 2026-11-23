package com.seastreams.tv.data.debrid.library

import com.seastreams.tv.data.debrid.api.DebridFile
import com.seastreams.tv.data.debrid.api.DebridItem
import com.seastreams.tv.data.usenet.newznab.UsenetSearchTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** PRD §14 *Matching*: which library items and files are the movie or episode asked for. */
class DebridLibraryMatcherTest {

    private val inception = UsenetSearchTarget.Movie("tt1375666", "Inception", 2010)

    private fun episode(season: Int, episode: Int) = UsenetSearchTarget.Episode("tt0903747", "Breaking Bad", season, episode)

    private fun file(path: String, size: Long = 8 * GB) = DebridFile(path, path, size)

    private fun item(name: String, vararg files: DebridFile) = DebridItem(name, name, null, files.toList().ifEmpty { null })

    /** The file paths [target] gets from [items], strongest item first. */
    private fun matches(target: DebridSearchTarget, vararg items: DebridItem): List<String> =
        DebridLibraryMatcher.rank(items.toList(), target).flatMap { ranked ->
            DebridLibraryMatcher.pick(ranked, ranked.item.files.orEmpty(), target).map { it.path }
        }

    @Test
    fun `a movie matches its title and a year within one, never another title, year or an episode`() {
        val found = matches(
            inception,
            item("Inception.2010.1080p.BluRay.x264-GRP", file("Inception.2010.1080p.BluRay.x264-GRP.mkv")),
            item("Inception (2011)", file("Inception.mkv")),
            item("Inception.2013.720p", file("Inception.2013.720p.mkv")),
            item("Inception.Making.Of.2010", file("Making.Of.mkv")),
            item("Inception.S01E01.2010", file("Inception.S01E01.mkv")),
            item("Interstellar.2014", file("Interstellar.2014.mkv")),
        )

        assertEquals(listOf("Inception.2010.1080p.BluRay.x264-GRP.mkv", "Inception.mkv"), found)
    }

    @Test
    fun `an IMDb tag in a folder name matches outright and ranks first`() {
        val ranked = DebridLibraryMatcher.rank(
            listOf(item("Inception.2010", file("a.mkv")), item("Nolan Collection/Dream heist [tt1375666]", file("heist.mkv"))),
            inception,
        )

        assertEquals(listOf("Nolan Collection/Dream heist [tt1375666]", "Inception.2010"), ranked.map { it.item.name })
    }

    @Test
    fun `samples, extras, non-video and tiny files are dropped from a movie folder`() {
        val found = matches(
            inception,
            item(
                "Inception.2010.2160p",
                file("Inception.2010.2160p/Inception.2010.2160p.mkv", 40 * GB),
                file("Inception.2010.2160p/Sample/inception-sample.mkv", 200 * MB),
                file("Inception.2010.2160p/Featurettes/Dreams.mkv", 3 * GB),
                file("Inception.2010.2160p/Inception.2010.2160p.nfo", 1),
                file("Inception.2010.2160p/Inception.2010.Recap.mkv", 900 * MB), // < 5% of 40 GB
            ),
        )

        assertEquals(listOf("Inception.2010.2160p/Inception.2010.2160p.mkv"), found)
    }

    @Test
    fun `a single episode matches SxxEyy and 1x02 tags of its show only`() {
        val items = arrayOf(
            item("Breaking.Bad.S01E02.720p.mkv", file("Breaking.Bad.S01E02.720p.mkv", GB)),
            item("Breaking Bad 1x02 Cats in the Bag", file("Breaking Bad 1x02.mkv", GB)),
            item("Breaking.Bad.S01E03.720p", file("Breaking.Bad.S01E03.720p.mkv", GB)),
            item("Breaking.Bad.Behaviour.S01E02", file("Breaking.Bad.Behaviour.S01E02.mkv", GB)),
            item("Breaking.Bad.S01E02.srt", file("Breaking.Bad.S01E02.srt", 1)),
        )

        assertEquals(listOf("Breaking.Bad.S01E02.720p.mkv", "Breaking Bad 1x02.mkv"), matches(episode(1, 2), *items))
    }

    @Test
    fun `multi-episode files and ranges cover each of their episodes`() {
        val double = item("Breaking.Bad.S02E01E02.1080p", file("Breaking.Bad.S02E01E02.1080p.mkv"))
        val range = item("Breaking.Bad.S02E03-E05.1080p", file("Breaking.Bad.S02E03-E05.1080p.mkv"))

        assertEquals(listOf("Breaking.Bad.S02E01E02.1080p.mkv"), matches(episode(2, 2), double, range))
        assertEquals(listOf("Breaking.Bad.S02E03-E05.1080p.mkv"), matches(episode(2, 4), double, range))
        assertEquals(emptyList<String>(), matches(episode(2, 6), double, range))
    }

    @Test
    fun `a season pack plays the episode's file, never a sample, and another season's pack is skipped`() {
        val pack = item(
            "Breaking.Bad.S01.1080p.BluRay",
            file("Breaking.Bad.S01.1080p.BluRay/Breaking.Bad.S01E01.mkv", 3 * GB),
            file("Breaking.Bad.S01.1080p.BluRay/Breaking.Bad.S01E02.mkv", 3 * GB),
            file("Breaking.Bad.S01.1080p.BluRay/Sample/Breaking.Bad.S01E02.sample.mkv", 50 * MB),
            file("Breaking.Bad.S01.1080p.BluRay/Breaking.Bad.S01E03.mkv", 3 * GB),
        )
        val other = item("Breaking.Bad.S02.1080p", file("Breaking.Bad.S02E02.mkv"))

        assertEquals(listOf("Breaking.Bad.S01.1080p.BluRay/Breaking.Bad.S01E02.mkv"), matches(episode(1, 2), other, pack))
    }

    @Test
    fun `nested folders of a complete series find the episode, also when files are named by number only`() {
        val complete = item(
            "Breaking Bad (2008) S01-S05 Complete",
            file("Breaking Bad (2008) S01-S05 Complete/Season 2/Breaking.Bad.S02E05.mkv"),
            file("Breaking Bad (2008) S01-S05 Complete/Season 2/Breaking.Bad.S02E06.mkv"),
        )
        val numbered = item(
            "Breaking Bad",
            file("Breaking Bad/Season 3/04 - Green Light.mkv"),
            file("Breaking Bad/Season 3/05 - Mas.mkv"),
            file("Breaking Bad/Season 4/05 - Shotgun.mkv"),
        )

        assertEquals(listOf("Breaking Bad (2008) S01-S05 Complete/Season 2/Breaking.Bad.S02E05.mkv"), matches(episode(2, 5), complete, numbered))
        assertEquals(listOf("Breaking Bad/Season 3/05 - Mas.mkv"), matches(episode(3, 5), complete, numbered))
    }

    @Test
    fun `a per-file item named by its full path is matched through its folders`() {
        val loose = item("TV/Breaking Bad/Season 1/02 - Cat's in the Bag.mkv", file("TV/Breaking Bad/Season 1/02 - Cat's in the Bag.mkv", GB))

        assertEquals(listOf(loose.name), matches(episode(1, 2), loose))
        assertTrue(matches(episode(1, 3), loose).isEmpty())
    }

    @Test
    fun `titles compare without accents, punctuation, a leading The or a country code`() {
        assertTrue(DebridLibraryMatcher.sameTitle("Amelie", "Amélie"))
        assertTrue(DebridLibraryMatcher.sameTitle("Office US", "The Office"))
        assertTrue(DebridLibraryMatcher.sameTitle("Spider Man No Way Home", "Spider-Man: No Way Home"))
        assertTrue(!DebridLibraryMatcher.sameTitle("Dark Angel", "Dark"))
    }

    private companion object {
        const val MB = 1024L * 1024
        const val GB = 1024L * MB
    }
}
