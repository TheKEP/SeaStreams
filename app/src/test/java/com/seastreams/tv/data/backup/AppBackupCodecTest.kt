package com.seastreams.tv.data.backup

import com.seastreams.tv.data.backup.BackupSamples.addon
import com.seastreams.tv.data.backup.BackupSamples.backup
import com.seastreams.tv.data.backup.BackupSamples.entry
import com.seastreams.tv.data.backup.BackupSamples.indexer
import com.seastreams.tv.data.backup.BackupSamples.provider
import com.seastreams.tv.domain.streams.RangeRule
import com.seastreams.tv.domain.streams.ValueRange
import com.seastreams.tv.domain.usenet.UsenetPerformance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppBackupCodecTest {

    @Test
    fun `profile and app backups round-trip unchanged`() {
        val profile = backup(BackupScope.PROFILE, entry("Ken", addons = listOf(BackupSamples.tmdb(), addon("https://torrentio.strem.fun/manifest.json", "Torrentio"))))
        val app = backup(
            BackupScope.APP, entry("Ken"), entry("Kids", kids = true),
            usenet = UsenetBackup(enabled = true, searchHeaders = "PROWLARR", providers = listOf(provider("pw")), indexers = listOf(indexer("key"))),
            debrid = DebridBackup(true, listOf(DebridProviderBackup("TORBOX", "TorBox", "API_KEY", credential = "torbox-api-key"))),
        )

        assertEquals(profile, AppBackupCodec.decode(AppBackupCodec.encode(profile)))
        val decoded = AppBackupCodec.decode(AppBackupCodec.encode(app))
        // The provider host is normalised to lower case; everything else comes back as written.
        assertEquals(app.copy(usenet = app.usenet.copy(providers = listOf(provider("pw").copy(host = "news.eweka.nl")))), decoded)
    }

    @Test
    fun `missing secrets are left out of the JSON entirely`() {
        val text = AppBackupCodec.encode(
            backup(
                BackupScope.APP, entry("Ken"),
                usenet = UsenetBackup(providers = listOf(provider()), indexers = listOf(indexer())),
                debrid = DebridBackup(providers = listOf(DebridProviderBackup("TORBOX", "TorBox", "API_KEY"))),
            ),
        )
        listOf("tmdbApiKey", "\"password\"", "\"apiKey\"", "\"credential\"", "refreshToken", "clientSecret", "null").forEach {
            assertFalse(text.contains(it), it)
        }
    }

    @Test
    fun `malformed files, other versions and unknown scopes are refused`() {
        assertMessage("That isn't a seastreams backup file") { AppBackupCodec.decode("{ not json") }
        assertMessage("That isn't a seastreams backup file") { AppBackupCodec.decode("""{"version":1}""") }
        assertMessage("Unsupported backup file version 2") { AppBackupCodec.decode(encoded(backup(BackupScope.APP, entry("Ken")).copy(version = 2))) }
        assertMessage("Unknown backup type “everything”") { AppBackupCodec.decode(encoded(backup(BackupScope.APP, entry("Ken")).copy(scope = "everything"))) }
    }

    @Test
    fun `profile counts and duplicate names are checked`() {
        assertMessage("A profile backup must hold exactly one profile") { decode(backup(BackupScope.PROFILE, entry("A"), entry("B"))) }
        assertMessage("The backup holds no profiles") { decode(backup(BackupScope.APP)) }
        assertMessage("A backup can hold at most 5 profiles") { decode(backup(BackupScope.APP, *(1..6).map { entry("P$it") }.toTypedArray())) }
        assertMessage("Two profiles in the backup share a name") { decode(backup(BackupScope.APP, entry("Ken"), entry("KEN"))) }
    }

    @Test
    fun `invalid regexes, profile fields, sources and manifests are refused`() {
        val badRegex = entry("Ken").let { e ->
            e.copy(streams = e.streams.copy(filters = e.streams.filters.copy(rules = e.streams.filters.rules.copy(regex = e.streams.filters.rules.regex.copy(required = listOf("(unclosed"))))))
        }
        assertMessage("Profile 1 (Ken): Not a valid regex: (unclosed") { decode(backup(BackupScope.PROFILE, badRegex)) }

        val adultCeiling = entry("Kids", kids = true).let { it.copy(profile = it.profile.copy(movieCeiling = "R")) }
        assertMessage("Profile “Kids”: Kids movie rating must be one of G, PG, PG-13") { decode(backup(BackupScope.APP, adultCeiling)) }
        val unknownCeiling = entry("Kids", kids = true).let { it.copy(profile = it.profile.copy(tvCeiling = "TV_XYZ")) }
        assertMessage("Profile “Kids” has no valid Kids ratings") { decode(backup(BackupScope.APP, unknownCeiling)) }
        assertMessage("Profile “ ”: Enter a profile name") { decode(backup(BackupScope.APP, entry(" "))) }

        val badHost = UsenetBackup(providers = listOf(provider().copy(host = "nntp://news.example.com")))
        assertMessage("Enter just the server address, e.g. news.example.com") { decode(backup(BackupScope.APP, entry("Ken"), usenet = badHost)) }
        val unknownDebrid = DebridBackup(providers = listOf(DebridProviderBackup("MEGA_DEBRID", "Mega", "API_KEY")))
        assertMessage("Unknown Debrid service “MEGA_DEBRID”") { decode(backup(BackupScope.APP, entry("Ken"), debrid = unknownDebrid)) }

        val notAnAddon = entry("Ken", addons = listOf(addon("ftp://nope", "Nope")))
        assertMessage("Profile 1 (Ken): “ftp://nope” isn't an addon URL") { decode(backup(BackupScope.APP, notAnAddon)) }
        val brokenManifest = entry("Ken", addons = listOf(addon("https://a.example/manifest.json", "A").copy(manifestJson = "[1,2")))
        assertMessage("Profile 1 (Ken): the manifest of A isn't valid JSON") { decode(backup(BackupScope.APP, brokenManifest)) }
    }

    @Test
    fun `out-of-range numbers are clamped, unknown header presets fall back, TMDB stays on`() {
        val wild = entry("Ken").let { e ->
            val rules = e.streams.filters.rules.copy(seeders = RangeRule(required = ValueRange(-5, 50_000_000)))
            e.copy(
                streams = e.streams.copy(filters = e.streams.filters.copy(rules = rules)),
                addons = listOf(BackupSamples.tmdb().copy(enabled = false)),
            )
        }
        val usenet = UsenetBackup(performance = UsenetPerformance(readAheadSegments = 9_999), searchHeaders = "NOT_A_PRESET")

        val decoded = decode(backup(BackupScope.PROFILE, wild, usenet = usenet))

        val seeders = decoded.profiles.single().streams.filters.rules.seeders.required
        assertEquals(ValueRange(0, StreamProcessingBackupBounds.MAX_SEEDERS), seeders)
        assertEquals(256, decoded.usenet.performance.readAheadSegments)
        assertNull(decoded.usenet.searchHeaders)
        assertTrue(decoded.profiles.single().addons.single().enabled, "TMDB can't be switched off")
    }

    @Test
    fun `a v1 file written before Library backups decodes with an empty Library and no watch progress`() {
        val legacy = AppBackupCodec.encode(backup(BackupScope.APP, entry("Ken")))
            .replace(Regex(",\\s*\"library\": \\[\\s*]"), "")
            .replace(Regex(",\\s*\"watchProgress\": \\[\\s*]"), "")
        assertFalse(legacy.contains("\"library\"") || legacy.contains("\"watchProgress\""), legacy)

        val profile = AppBackupCodec.decode(legacy).profiles.single()

        assertEquals(emptyList<LibraryItemBackup>(), profile.library)
        assertEquals(emptyList<WatchProgressBackup>(), profile.watchProgress)
    }

    @Test
    fun `Library and watch progress round-trip with every column`() {
        val withHistory = entry("Ken").copy(
            library = listOf(LibraryItemBackup(550, "MOVIE", "Fight Club", "/p.jpg", "R", "18,53", 1_700_000_000_000)),
            watchProgress = listOf(
                WatchProgressBackup(550, "MOVIE", title = "Fight Club", posterPath = "/p.jpg", backdropPath = "/b.jpg", positionMs = 42_000, durationMs = 8_340_000, updatedAtMillis = 5),
                WatchProgressBackup(1399, "TV", 1, 2, "Game of Thrones", "The Kingsroad", null, null, 3_600_000, 3_600_000, 6, dismissedFromContinueWatching = true),
            ),
        )
        val backup = backup(BackupScope.PROFILE, withHistory)

        assertEquals(backup, decode(backup))
    }

    @Test
    fun `invalid Library and watch progress entries are refused before anything is written`() {
        val movie = WatchProgressBackup(550, "MOVIE", title = "Fight Club", positionMs = 1, durationMs = 10, updatedAtMillis = 1)
        val item = LibraryItemBackup(550, "MOVIE", "Fight Club", addedAtMillis = 1)
        fun withLibrary(vararg items: LibraryItemBackup) = backup(BackupScope.APP, entry("Ken").copy(library = items.toList()))
        fun withProgress(vararg rows: WatchProgressBackup) = backup(BackupScope.APP, entry("Ken").copy(watchProgress = rows.toList()))

        assertMessage("Profile 1 (Ken): Library title 1 has an invalid TMDB id") { decode(withLibrary(item.copy(tmdbId = 0))) }
        assertMessage("Profile 1 (Ken): Library title 1 has an unknown media type “SHOW”") { decode(withLibrary(item.copy(mediaType = "SHOW"))) }
        assertMessage("Profile 1 (Ken): Library title 1 has no valid title") { decode(withLibrary(item.copy(title = "  "))) }
        assertMessage("Profile 1 (Ken): Library title 1 has invalid genre ids") { decode(withLibrary(item.copy(genreIds = "28;drop"))) }
        assertMessage("Profile 1 (Ken) lists a Library title twice") { decode(withLibrary(item, item.copy(title = "Again"))) }
        assertMessage("Profile 1 (Ken): watch progress entry 1 has a negative position or duration") { decode(withProgress(movie.copy(positionMs = -1))) }
        assertMessage("Profile 1 (Ken): watch progress entry 1 is a movie with a season or episode") { decode(withProgress(movie.copy(seasonNumber = 1))) }
        assertMessage("Profile 1 (Ken): watch progress entry 1 has a negative season or episode") {
            decode(withProgress(movie.copy(mediaType = "TV", episodeNumber = -2)))
        }
        assertMessage("Profile 1 (Ken) lists the same watch progress entry twice") { decode(withProgress(movie, movie.copy(positionMs = 5))) }
    }

    private fun encoded(backup: AppBackup) = AppBackupCodec.encode(backup)

    private fun decode(backup: AppBackup) = AppBackupCodec.decode(encoded(backup))

    private fun assertMessage(expected: String, block: () -> Unit) {
        val error = assertThrows(InvalidAppBackupException::class.java) { block() }
        assertEquals(expected, error.message)
    }

    private object StreamProcessingBackupBounds {
        const val MAX_SEEDERS = com.seastreams.tv.core.datastore.StreamProcessingBackupCodec.MAX_SEEDERS
    }
}
