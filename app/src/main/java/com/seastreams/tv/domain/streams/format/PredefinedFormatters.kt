package com.seastreams.tv.domain.streams.format

import com.seastreams.tv.domain.streams.FormatterId

/** A formatter's name and description templates. */
data class FormatterTemplates(val name: String, val description: String)

/**
 * The predefined formatters of PRD §13: AIOStreams' built-in Torrentio, GDrive, Light GDrive, Minimalistic GDrive and
 * Prism definitions (`utils/formatter-definitions.ts`, verbatim except that `{addon.name}` becomes [SOURCE] and
 * Minimalistic GDrive also shows `{stream.indexer}`), and [CUSTOM_DEFAULT], the `aio-formatter.png`
 * template a Custom formatter starts from until the user types their own in the Web Portal.
 */
object PredefinedFormatters {
    val TORRENTIO = withSource(FormatterTemplates(
        name = """{stream.proxied["🕵️‍♂️ "||""]}{stream.private["🔑 "||""]}{stream.type::=p2p["[P2P] "||""]}{service.id::exists["[{service.shortName}"||""]}{service.cached["+] "||" download] "]}{addon.name} {stream.resolution::exists["{stream.resolution}"||"Unknown"]}
{?{stream.visualTags::join(' | ')}?}""",
        description = """{?ℹ️{stream.message}?}
{?{stream.folderName}?}
{?{stream.filename}?}
{stream.size::>0["💾{stream.size::bytes2} "||""]}{stream.folderSize::>0["/ 💾{stream.folderSize::bytes2}"||""]}{stream.seeders::>=0["👤{stream.seeders} "||""]}{?📅{stream.age} ?}{?⚙️{stream.indexer}?}
{?{stream.languageEmojis::join(' / ')}?}{stream.subtitles::exists::and::stream.languageEmojis::exists[" "||""]}{stream.subtitles::exists["Subs / {stream.subtitleEmojis::join(' / ')}"||""]}
""",
    ))
    val GDRIVE = withSource(FormatterTemplates(
        name = """{stream.proxied["🕵️ "||""]}{stream.private["🔑 "||""]}{stream.type::=p2p["[P2P] "||""]}{?[{service.shortName}?}{service.cached["⚡] "||"⏳] "]}{addon.name}{stream.library[" (Your Media)"||""]} {?{stream.resolution}?}{stream.seadexBest[" (Best)"||""]}{stream.seadex::and::stream.seadexBest::isfalse[" (SeaDex Alt.)"||""]}{stream.rseMatched::exists::and::stream.seadex::isfalse::and::stream.rseMatched::string::~T1::or::stream.rseMatched::string::~T2::or::stream.rseMatched::string::~T3::or::stream.rseMatched::string::~T4::or::stream.rseMatched::string::~T5::or::stream.rseMatched::string::~T6::or::stream.rseMatched::string::~T7::or::stream.rseMatched::string::~T8[" ({stream.rseMatched::first})"||""]}{stream.regexMatched::exists::and::stream.rseMatched::exists::isfalse::and::stream.seadex::isfalse[" ({stream.regexMatched})"||""]}""",
        description = """{?🎥 {stream.quality} ?}{?🎞️ {stream.encode} ?}{?🏷️ {stream.releaseGroup} ?}{?📡 {stream.network} ?}{stream.editions::exists["🏆 {stream.editions::join(' | ')} "||""]}
{?📺 {stream.visualTags::join(' | ')} ?}{?🎧 {stream.audioTags::join(' | ')} ?}{?🔊 {stream.audioChannels::join(' | ')}?}
{stream.size::>0["📦 {stream.size::sbytes} "||""]}{stream.folderSize::>0["/ {stream.folderSize::sbytes} "||""]}{stream.bitrate::>0["({stream.bitrate::sbitrate})"||""]}{stream.duration::>0["⏱️ {stream.duration::time} "||""]}{stream.seeders::>0["👥 {stream.seeders} "||""]}{?📅 {stream.age} ?}{?🔍 {stream.indexer}?}
{?🌎 {stream.languages::join(' | ')}?}{?📝 {stream.subtitles::join(' | ')}?}
{stream.filename::exists["📁"||""]} {?{stream.folderName}/?}{?{stream.filename}?}
{?ℹ️ {stream.message}?}
      """,
    ))
    val LIGHT_GDRIVE = withSource(FormatterTemplates(
        name = """{stream.proxied["🕵️ "||""]}{stream.private["🔑 "||""]}{stream.type::=p2p["[P2P] "||""]}{?[{service.shortName}?}{stream.library["☁️"||""]}{service.cached["⚡] "||"⏳] "]}{addon.name}{? {stream.resolution}?}{stream.seadexBest[" (Best)"||""]}{stream.seadex::and::stream.seadexBest::isfalse[" (SeaDex Alt.)"||""]}{stream.rseMatched::exists::and::stream.seadex::isfalse::and::stream.rseMatched::string::~T1::or::stream.rseMatched::string::~T2::or::stream.rseMatched::string::~T3::or::stream.rseMatched::string::~T4::or::stream.rseMatched::string::~T5::or::stream.rseMatched::string::~T6::or::stream.rseMatched::string::~T7::or::stream.rseMatched::string::~T8[" ({stream.rseMatched::first})"||""]}{stream.regexMatched::exists::and::stream.rseMatched::exists::isfalse::and::stream.seadex::isfalse[" ({stream.regexMatched})"||""]}""",
        description = """{?📁 {stream.title::title}?}{? ({stream.year})?}{? {stream.seasonEpisode::join(' • ')}?}
{?🎥 {stream.quality} ?}{?🎞️ {stream.encode} ?}{?🏷️ {stream.releaseGroup}?}{?📡 {stream.network} ?}{stream.editions::exists[" 🏆 {stream.editions::join(' • ')}"||""]}
{?📺 {stream.visualTags::join(' • ')} ?}{?🎧 {stream.audioTags::join(' • ')} ?}{?🔊 {stream.audioChannels::join(' • ')}?}
{stream.size::>0["📦 {stream.size::sbytes} "||""]}{stream.folderSize::>0["/ {stream.folderSize::sbytes} "||""]}{stream.duration::>0["⏱️ {stream.duration::time} "||""]}{?📅 {stream.age} ?}{?🔍 {stream.indexer}?}
{?🌐 {stream.languageEmojis::join(' / ')}?}{stream.subtitles::exists["📝 {stream.subtitleEmojis::join(' / ')}"||""]}
{?ℹ️ {stream.message}?}""",
    ))
    val MINIMALISTIC_GDRIVE = FormatterTemplates(
        name = """{stream.resolution::exists["{stream.resolution::replace('2160p','✨ 4K')::replace('1440p','📀 2K')::replace('1080p','🧿1080p')::replace('720p','💿720p')}"||"N/A"]}{service.cached[" 🎫 "||" 🎟️ "]}
{?{stream.quality::upper}?}
""",
        description = """{?🔆 {stream.visualTags::join(' • ')}  ?}{?🔊 {stream.audioTags::join(' • ')}?}
{stream.size::>0["📦 {stream.size::sbytes} "||""]}{?🔍 {stream.indexer}?}
{?🌎 {stream.languages::join(' • ')}?}{?📝 {stream.subtitles::join(' • ')}?}
""",
    )
    val PRISM = withSource(FormatterTemplates(
        name = """{stream.resolution::exists["{stream.resolution::replace('2160p', '🔥4K UHD')::replace('1440p','✨ QHD')::replace('1080p','🚀 FHD')::replace('720p','💿 HD')::replace('576p','💩 Low Quality')::replace('480p','💩 Low Quality')::replace('360p','💩 Low Quality')::replace('240p','💩 Low Quality')::replace('144p','💩 Low Quality')}"||"💩 Unknown"]}""",
        description = """{?🎬 {stream.title::title} ?}{?({stream.year}) ?}{?🍂 {stream.formattedSeasons} ?}{?🎞️ {stream.formattedEpisodes}?}{stream.seadexBest["🎚️ Best "||""]}{stream.seadex::and::stream.seadexBest::isfalse["🎚️ Alternative"||""]}{stream.rseMatched::exists::and::stream.seadex::isfalse::and::stream.rseMatched::string::~T1::or::stream.rseMatched::string::~T2::or::stream.rseMatched::string::~T3::or::stream.rseMatched::string::~T4::or::stream.rseMatched::string::~T5::or::stream.rseMatched::string::~T6::or::stream.rseMatched::string::~T7::or::stream.rseMatched::string::~T8[" 🎚️ {stream.rseMatched::first}"||""]}{stream.regexMatched::exists::and::stream.rseMatched::exists::isfalse::and::stream.seadex::isfalse["🎚️ {stream.regexMatched} "||""]}
{?🎥 {stream.quality} ?}{?📺 {stream.visualTags::join(' | ')} ?}{?🎞️ {stream.encode} ?}{stream.duration::>0["⏱️ {stream.duration::time} "||""]}{stream.editions::exists["🏆 {stream.editions::join(' | ')} "||""]}
{?🎧 {stream.audioTags::join(' | ')} ?}{?🔊 {stream.audioChannels::join(' | ')} ?}{stream.languages::exists["🗣️ {stream.languageEmojis::join(' / ')}"||""]}{stream.subtitles::exists["📝 {stream.subtitleEmojis::join(' / ')}"||""]}
{stream.size::>0["📦 {stream.size::sbytes} "||""]}{stream.folderSize::>0["/ {stream.folderSize::sbytes} "||""]}{stream.bitrate::>0["📊 {stream.bitrate::sbitrate} "||""]}{service.cached::isfalse::or::stream.type::=p2p::and::stream.seeders::>0["🌱 {stream.seeders} "||""]}{stream.type::=usenet::and::stream.age::exists["📅 {stream.age} "||""]}
{?🏷️ {stream.releaseGroup} ?}{?📡 {stream.indexer} ?}{?🎭 {stream.network}?}
{service.cached["⚡Ready "||"❌ Not Ready "]}{service.id::exists["({service.shortName}) "||""]}{stream.library["📌 Library "||""]}{stream.type::=Usenet["📰 Usenet "||""]}{stream.type::=p2p["⚠️ P2P "||""]}{stream.type::=http["💻 Web Link "||""]}{stream.type::=youtube["▶️ Youtube "||""]}{stream.type::=live["📺 Live "||""]}{stream.proxied["🔒 Proxied "||""]}{stream.private["🔑 Private "||""]}🔍{addon.name} 
{?ℹ️ {stream.message}?}
""",
    ))
    /**
     * Where a stream came from, for the templates' `{addon.name}`: a built-in `Usenet` stream shows its indexer
     * (`NZBgeek`, `DrunkenSlug`) and a built-in `Debrid` stream its service (`Real-Debrid`, `TorBox`) instead of
     * `Usenet` / `Debrid` (the Addons column still groups them under one card each). Conditions read left to right:
     * (Usenet or Debrid) and an indexer is known.
     */
    const val SOURCE = """{addon.name::=Usenet::or::addon.name::=Debrid::and::stream.indexer::exists["{stream.indexer}"||"{addon.name}"]}"""

    /** AIOStreams' templates with each `{addon.name}` replaced by [SOURCE]. */
    private fun withSource(templates: FormatterTemplates) = FormatterTemplates(
        name = templates.name.replace(ADDON_NAME, SOURCE),
        description = templates.description.replace(ADDON_NAME, SOURCE),
    )

    private const val ADDON_NAME = "{addon.name}"

    /**
     * The template shown in `aio-formatter.png`. Its last line names the source: the indexer for a Usenet stream (the
     * service for a Debrid one), else
     * the addon followed by the indexer when the addon states one (`Torrentio · ThePirateBay`).
     */
    val CUSTOM_DEFAULT = FormatterTemplates(
        name = """{stream.resolution::=2160p["🔥 4K"||""]}{stream.resolution::=1440p["✨ 1440p"||""]}{stream.resolution::=1080p["🚀 1080p"||""]}{stream.resolution::=720p["💿 720p"||""]}{stream.resolution::=576p["SD"||""]}{stream.resolution::=480p["📀 DVD"||""]} {stream.title::exists["🎬 {stream.title} "||""]}{stream.year::exists["({stream.year}) "||""]}{stream.seasonEpisode::exists["📺 {stream.seasonEpisode::join(' ')} "||""]}{stream.edition::exists["⭐ {stream.edition} "||""]}""",
        description = """{stream.filename}
{stream.quality::exists["🎥 {stream.quality} "||""]}{stream.encode::exists["🎞️ {stream.encode} "||""]}{stream.visualTags::exists["🔆 {stream.visualTags::join(' | ')} "||""]}
{stream.audioTags::exists["🎧 {stream.audioTags::join(' | ')} "||""]}{stream.audioChannels::exists["🔊 {stream.audioChannels::join(' | ')}"||""]} {stream.size::>0["📦 {stream.size::rbytes} "||""]}{stream.duration::>0["⏱️ {stream.duration::time} "||""]}{stream.age::exists["📅 {stream.age} "||""]}
{stream.languages::exists["🗣️ {stream.languageEmojis} "||""]}{stream.network::exists["📡 {stream.network} "||""]}| {addon.name::=Usenet::or::addon.name::=Debrid::and::stream.indexer::exists["{stream.indexer}"||"{addon.name}{stream.indexer::exists[" · {stream.indexer}"||""]}"]}""",
    )

    /** [id]'s templates; `null` for [FormatterId.ORIGINAL] (the addon's own text). Custom falls back to [CUSTOM_DEFAULT]. */
    fun templates(id: FormatterId, customName: String? = null, customDescription: String? = null): FormatterTemplates? = when (id) {
        FormatterId.ORIGINAL -> null
        FormatterId.TORRENTIO -> TORRENTIO
        FormatterId.GDRIVE -> GDRIVE
        FormatterId.LIGHT_GDRIVE -> LIGHT_GDRIVE
        FormatterId.MINIMALISTIC_GDRIVE -> MINIMALISTIC_GDRIVE
        FormatterId.PRISM -> PRISM
        FormatterId.CUSTOM -> FormatterTemplates(
            name = customName?.takeIf { it.isNotBlank() } ?: CUSTOM_DEFAULT.name,
            description = customDescription?.takeIf { it.isNotBlank() } ?: CUSTOM_DEFAULT.description,
        )
    }
}
