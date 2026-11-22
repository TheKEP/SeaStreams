package com.seastreams.tv.data.usenet.presets

/** A well-known provider's secure NNTP server (PRD §12). Every field stays editable after picking one. */
data class ProviderPreset(
    val name: String,
    val host: String,
    val maxConnections: Int,
    val port: Int = DEFAULT_TLS_PORT,
    val tls: Boolean = true,
)

/** A well-known Newznab indexer's API address; the two LAN aggregator entries are examples to edit. */
data class IndexerPreset(
    val name: String,
    val baseUrl: String,
    val apiPath: String = "/api",
    /** `false` for feeds that answer without an API key (Anime Tosho). */
    val needsApiKey: Boolean = true,
)

const val DEFAULT_TLS_PORT = 563

/** Provider presets, ported from Nabrio/POV (`UsenetSettings.kt`); AIOStreams has no host table. */
object UsenetProviderPresets {
    val ALL: List<ProviderPreset> = listOf(
        ProviderPreset("Newshosting", "news.newshosting.com", 100),
        ProviderPreset("Easynews", "news.easynews.com", 60),
        ProviderPreset("Tweaknews (Newshosting deal)", "newshosting.tweaknews.eu", 40),
        ProviderPreset("Tweaknews (standalone)", "news.tweaknews.eu", 40),
        ProviderPreset("Eweka", "news.eweka.nl", 50),
        ProviderPreset("UsenetServer", "news.usenetserver.com", 20),
        ProviderPreset("Giganews", "news.giganews.com", 100),
        ProviderPreset("Frugal Usenet", "news.frugalusenet.com", 100),
        ProviderPreset("NewsDemon", "news.newsdemon.com", 50),
        ProviderPreset("Usenet.Farm", "news.usenet.farm", 40),
        ProviderPreset("UsenetExpress", "news.usenetexpress.com", 50),
        ProviderPreset("NewsgroupDirect", "news.newsgroupdirect.com", 100),
        ProviderPreset("Newsgroup Ninja", "news.newsgroup.ninja", 50),
        ProviderPreset("Supernews", "news.supernews.com", 30),
        ProviderPreset("Pure Usenet", "news.pureusenet.nl", 20),
        ProviderPreset("Sunny Usenet", "news.sunnyusenet.com", 20),
        ProviderPreset("XS News", "reader.xsnews.nl", 50),
        ProviderPreset("ThunderNews", "news.thundernews.com", 60),
        ProviderPreset("Astraweb", "news.astraweb.com", 20),
        ProviderPreset("ViperNews", "news.vipernews.com", 60),
        ProviderPreset("Cheapnews", "news.cheapnews.eu", 30),
        ProviderPreset("Fast Usenet", "news.fastusenet.org", 50),
        ProviderPreset("UsenetNow", "news.usenetnow.net", 50),
        ProviderPreset("UsenetPrime", "news.usenetprime.com", 50),
        ProviderPreset("Easy Usenet", "news.easyusenet.nl", 20),
        ProviderPreset("Bulknews", "news.bulknews.eu", 30),
        ProviderPreset("XLned", "news.xlned.com", 20),
        ProviderPreset("123 Usenet", "news.123usenet.nl", 20),
        ProviderPreset("Hitnews", "news.hitnews.com", 20),
    )
}

/** Indexer presets: AIOStreams' `presets/newznab.ts` list plus Nabrio's NZBHydra2 / Prowlarr LAN templates. */
object IndexerPresets {
    val ALL: List<IndexerPreset> = listOf(
        IndexerPreset("abNZB", "https://abnzb.com"),
        IndexerPreset("altHUB", "https://api.althub.co.za"),
        IndexerPreset("AmeNZB", "https://amenzb.moe"),
        IndexerPreset("Anime Tosho", "https://feed.animetosho.net", needsApiKey = false),
        IndexerPreset("Aninzb", "https://aninzb.moe"),
        IndexerPreset("ClubNZB", "https://clubnzb.com"),
        IndexerPreset("Digital Carnage", "https://digitalcarnage.info"),
        IndexerPreset("DOGnzb", "https://api.dognzb.cr"),
        IndexerPreset("DrunkenSlug", "https://drunkenslug.com"),
        IndexerPreset("GingaDADDY", "https://www.gingadaddy.com"),
        IndexerPreset("Miatrix", "https://www.miatrix.com"),
        IndexerPreset("NewzBay", "https://newzbay.cc"),
        IndexerPreset("NinjaCentral", "https://ninjacentral.co.za"),
        IndexerPreset("NZB Atlas", "https://nzbatlas.com"),
        IndexerPreset("Nzb.life", "https://api.nzb.life"),
        IndexerPreset("NZBFinder", "https://nzbfinder.ws"),
        IndexerPreset("NZBgeek", "https://api.nzbgeek.info"),
        IndexerPreset("NzbNest", "https://nzbnest.com"),
        IndexerPreset("NzbNoob", "https://nzbnoob.com"),
        IndexerPreset("NzbPlanet", "https://api.nzbplanet.net"),
        IndexerPreset("NZBStars", "https://nzbstars.com"),
        IndexerPreset("PourCeSoir", "https://pourcesoir.in"),
        IndexerPreset("SearchNZB", "https://api.searchnzb.com"),
        IndexerPreset("SquareEyed", "https://squareeyed.org"),
        IndexerPreset("Tabula Rasa", "https://www.tabula-rasa.pw", apiPath = "/api/v1/api"),
        IndexerPreset("Treasure Maps", "https://treasure-maps.com"),
        IndexerPreset("Usenet Crawler", "https://www.usenet-crawler.com"),
        IndexerPreset("Videothek", "https://videothek.io"),
        IndexerPreset("NZBHydra2 (your server)", "http://192.168.1.10:5076"),
        IndexerPreset("Prowlarr (your server)", "http://192.168.1.10:9696", apiPath = "/1/api"),
    )
}
