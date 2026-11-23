package com.seastreams.tv.data.usenet.newznab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A trimmed real-world Newznab RSS answer (NZBgeek-style, `extended=1`). */
internal val SEARCH_FIXTURE = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE rss SYSTEM "http://indexer.invalid/never-fetched.dtd">
<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom" xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/">
<channel>
<title>indexer</title>
<newznab:response offset="0" total="2"/>
<item>
  <title>Dune.Part.Two.2024.2160p.UHD.BluRay.x265-GROUP</title>
  <guid isPermaLink="true">https://indexer.invalid/details/abc123</guid>
  <link>https://indexer.invalid/api?t=get&amp;id=abc123&amp;apikey=SECRET</link>
  <pubDate>Sat, 20 Apr 2024 10:00:00 +0000</pubDate>
  <enclosure url="https://indexer.invalid/api?t=get&amp;id=abc123&amp;apikey=SECRET" length="53687091200" type="application/x-nzb"/>
  <newznab:attr name="category" value="2000"/>
  <newznab:attr name="size" value="53687091200"/>
  <newznab:attr name="imdb" value="15239678"/>
  <newznab:attr name="password" value="0"/>
</item>
<item>
  <title><![CDATA[Dune Part Two 2024 1080p WEB & Friends]]></title>
  <guid>def456</guid>
  <link>https://indexer.invalid/getnzb/def456.nzb</link>
  <newznab:attr name="size" value="8000000000"/>
  <newznab:attr name="password" value="1"/>
</item>
<item>
  <title>No link here</title>
</item>
</channel>
</rss>"""

internal val CAPS_FIXTURE = """<?xml version="1.0" encoding="UTF-8"?>
<caps>
  <server version="1.0" title="indexer"/>
  <limits max="100" default="100"/>
  <searching>
    <search available="yes" supportedParams="q"/>
    <tv-search available="yes" supportedParams="q,season,ep,imdbid,tvdbid"/>
    <movie-search available="yes" supportedParams="q,imdbid"/>
  </searching>
  <categories>
    <category id="2000" name="Movies"><subcat id="2040" name="HD"/></category>
    <category id="5000" name="TV"/>
  </categories>
</caps>"""

class NewznabParsingTest {

    @Test
    fun `search items carry title, link, size, date and attributes`() {
        val items = NewznabXmlScanner.items(SEARCH_FIXTURE)
        assertEquals(2, items.size)
        val first = items[0]
        assertEquals("Dune.Part.Two.2024.2160p.UHD.BluRay.x265-GROUP", first.title)
        assertEquals("https://indexer.invalid/api?t=get&id=abc123&apikey=SECRET", first.nzbUrl)
        assertEquals(53_687_091_200, first.sizeBytes)
        assertEquals("15239678", first.imdbId)
        assertEquals(1_713_607_200_000, first.publishedMillis)
        assertFalse(first.passworded)
        assertEquals("Dune Part Two 2024 1080p WEB & Friends", items[1].title)
        assertTrue(items[1].passworded)
    }

    @Test
    fun `error answers map codes 100 to 104 to account problems`() {
        val error = NewznabXmlScanner.error("""<?xml version="1.0"?><error code="100" description="Incorrect user credentials"/>""")
        assertEquals(100, error?.code)
        assertTrue(error?.accountProblem == true)
        assertEquals("The indexer rejected the API key", error?.message)
        assertEquals(false, NewznabXmlScanner.error("""<error code="201" description="Incorrect parameter"/>""")?.accountProblem)
        assertNull(NewznabXmlScanner.error(SEARCH_FIXTURE))
    }

    @Test
    fun `caps list the search modes and their parameters`() {
        val caps = NewznabXmlScanner.caps(CAPS_FIXTURE)
        assertTrue(caps.movieSearch.supports("imdbid"))
        assertTrue(caps.tvSearch.supports("ep"))
        assertFalse(caps.search.supports("imdbid"))
        assertEquals(setOf(2000, 5000), caps.categories)
    }

    @Test
    fun `entities decode, including numeric ones`() {
        assertEquals("a & b <c> \"d\" 'e' é", NewznabXmlScanner.unescape("a &amp; b &lt;c&gt; &quot;d&quot; &apos;e&apos; &#233;"))
        assertEquals("&bogus; stays", NewznabXmlScanner.unescape("&bogus; stays"))
    }

    @Test
    fun `movie plans search by IMDb id then fall back to text with the year`() {
        val caps = NewznabXmlScanner.caps(CAPS_FIXTURE)
        val plan = NewznabQueries.plan(caps, UsenetSearchTarget.Movie("tt15239678", "Dune: Part Two", 2024))
        assertEquals(2, plan.size)
        assertEquals(
            listOf("t" to "movie", "imdbid" to "15239678", "cat" to "2000", "extended" to "1", "limit" to "100"),
            plan[0].params,
        )
        assertTrue(plan[0].byId)
        assertEquals("Dune Part Two 2024", plan[1].param("q"))
        assertFalse(plan[1].byId)
    }

    @Test
    fun `episode plans use tvsearch with season and ep, and text search when ids aren't supported`() {
        val caps = NewznabXmlScanner.caps(CAPS_FIXTURE)
        val plan = NewznabQueries.plan(caps, UsenetSearchTarget.Episode("tt0903747", "Breaking Bad", 5, 14))
        assertEquals(
            listOf("t" to "tvsearch", "imdbid" to "0903747", "season" to "5", "ep" to "14", "cat" to "5000", "extended" to "1", "limit" to "100"),
            plan[0].params,
        )
        val textOnly = NewznabQueries.plan(NewznabCaps(), UsenetSearchTarget.Episode("tt0903747", "Breaking Bad", 5, 14)).single()
        assertEquals("search", textOnly.param("t"))
        assertEquals("Breaking Bad S05E14", textOnly.param("q"))
    }

    @Test
    fun `the API key is appended last and only at send time`() {
        val endpoint = NewznabEndpoint(1, "Geek", "https://api.indexer.invalid/api", "k3y", 0, false)
        val url = NewznabClient.requestUrl(endpoint, NewznabQueries.CAPS).toString()
        assertEquals("https://api.indexer.invalid/api?t=caps&o=xml&apikey=k3y", url)
        assertFalse(endpoint.toString().contains("k3y"))
    }

    @Test
    fun `release matching checks year and episode tags`() {
        val movie = UsenetSearchTarget.Movie("tt1", "Dune Part Two", 2024)
        assertTrue(ReleaseMatch.matches("Dune.Part.Two.2024.2160p.UHD", movie, strict = true))
        assertTrue(ReleaseMatch.matches("Dune.Part.Two.2023.1080p", movie, strict = true))
        assertFalse(ReleaseMatch.matches("Dune.Part.Two.2021.1080p", movie, strict = false))
        assertFalse(ReleaseMatch.matches("Dune.Part.Two.S01E01.1080p", movie, strict = false))
        assertFalse(ReleaseMatch.matches("Dune.2021.2160p", movie, strict = true))
        assertTrue(ReleaseMatch.matches("Dune Part Two 1080p", movie, strict = false))
        assertFalse(ReleaseMatch.matches("Dune Part Two 1080p", movie, strict = true))

        val episode = UsenetSearchTarget.Episode("tt2", "Breaking Bad", 5, 14)
        assertTrue(ReleaseMatch.matches("Breaking.Bad.S05E14.Ozymandias.1080p", episode, strict = true))
        assertTrue(ReleaseMatch.matches("Breaking.Bad.S05E13-E14.720p", episode, strict = true))
        assertTrue(ReleaseMatch.matches("Breaking Bad 5x14 720p", episode, strict = true))
        assertFalse(ReleaseMatch.matches("Breaking.Bad.S05E15.1080p", episode, strict = false))
        assertFalse(ReleaseMatch.matches("Breaking.Bad.S05E02.720p", episode, strict = false))
        assertFalse(ReleaseMatch.matches("Breaking.Bad.S05.COMPLETE.1080p", episode, strict = false))
        assertFalse(ReleaseMatch.matches("Breaking.Bad.S04E14.1080p", episode, strict = false))
        assertTrue(ReleaseMatch.matches("Breaking.Bad.S05E14.720p", episode.copy(title = "The Breaking Bad"), strict = true))
    }
}
