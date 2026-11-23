package com.seastreams.tv.domain.streams.parse

import java.util.Locale

/**
 * The stream languages SeaStreams recognises, ported from AIOStreams (`PARSE_REGEX.languages`, `languageEmojiMap`,
 * `languageToCode`). Order matters: release-name languages are listed in this order, as in AIOStreams.
 *
 * [Entry.pattern] is the release-name token pattern (`null` = only detected from flags); [Entry.code] the upper-case
 * ISO 639-1 code the formatter's `languageCodes` show; [Entry.emoji] the formatter's `languageEmojis` flag; and
 * [Entry.flags] the flag emojis in an addon's text that mean this language.
 */
object LanguageTable {

    class Entry(val name: String, val pattern: String?, val code: String?, val emoji: String?, val flags: List<String> = listOfNotNull(emoji))

    val ALL: List<Entry> = listOf(
        Entry("Multi", "multi", null, "🌎"),
        Entry("Dual Audio", "dual[ .\\-_]?(audio|lang(uage)?|flac|ac3|aac2?)", null, null),
        Entry("Dubbed", "dub(s|bed|bing)?", null, null),
        Entry("English", "english|eng", "EN", "🇬🇧", listOf("🇬🇧", "🇺🇸", "🇦🇺", "🇨🇦")),
        Entry("Japanese", "japanese|jap|jpn", "JA", "🇯🇵"),
        Entry("Chinese", "chinese|chi", "ZH", "🇨🇳", listOf("🇨🇳", "🇹🇼", "🇭🇰")),
        Entry("Russian", "russian|rus", "RU", "🇷🇺"),
        Entry("Arabic", "arabic|ara", "AR", "🇸🇦", listOf("🇸🇦", "🇦🇪", "🇪🇬")),
        Entry("Portuguese", "portuguese", "PT", "🇵🇹"),
        Entry("Portuguese (Brazil)", "portuguese[ .\\-_]?brazil|pt[ .\\-_]?br", "PT", "🇧🇷"),
        Entry("Spanish", "spanish|spa|esp", "ES", "🇪🇸"),
        Entry("French", "french|fra|fr|vf|vff|vfi|vf2|vfq|truefrench", "FR", "🇫🇷"),
        Entry("German", "deu(tsch)?(land)?|ger(man)?", "DE", "🇩🇪", listOf("🇩🇪", "🇦🇹")),
        Entry("Italian", "italian|ita", "IT", "🇮🇹"),
        Entry("Korean", "korean|kor", "KO", "🇰🇷"),
        Entry("Hindi", "hindi|hin", "HI", "🇮🇳"),
        Entry("Bengali", "bengali|ben(?![ .\\-_]?the[ .\\-_]?men)", "BN", "🇧🇩"),
        Entry("Punjabi", "punjabi|pan", "PA", "🇵🇰"),
        Entry("Marathi", "marathi|mar", "MR", "🇮🇳", emptyList()),
        Entry("Gujarati", "gujarati|guj", "GU", "🇮🇳", emptyList()),
        Entry("Tamil", "tamil|tam", "TA", "🇮🇳", emptyList()),
        Entry("Telugu", "telugu|tel", "TE", "🇮🇳", emptyList()),
        Entry("Kannada", "kannada|kan", "KN", "🇮🇳", emptyList()),
        Entry("Malayalam", "malayalam|mal", "ML", "🇮🇳", emptyList()),
        Entry("Thai", "thai|tha", "TH", "🇹🇭"),
        Entry("Vietnamese", "vietnamese|vie", "VI", "🇻🇳"),
        Entry("Indonesian", "indonesian|ind", "ID", "🇮🇩"),
        Entry("Turkish", "turkish|tur", "TR", "🇹🇷"),
        Entry("Hebrew", "hebrew|heb", "HE", "🇮🇱"),
        Entry("Persian", "persian|per", "FA", "🇮🇷"),
        Entry("Ukrainian", "ukrainian|ukr", "UK", "🇺🇦"),
        Entry("Greek", "greek|ell", "EL", "🇬🇷"),
        Entry("Lithuanian", "lithuanian|lit", "LT", "🇱🇹"),
        Entry("Latvian", "latvian|lav", "LV", "🇱🇻"),
        Entry("Estonian", "estonian|est", "ET", "🇪🇪"),
        Entry("Polish", "polish|pol", "PL", "🇵🇱"),
        Entry("Czech", "czech|cze", "CS", "🇨🇿"),
        Entry("Slovak", "slovak|slo", "SK", "🇸🇰"),
        Entry("Hungarian", "hungarian|hun", "HU", "🇭🇺"),
        Entry("Romanian", "romanian|rum", "RO", "🇷🇴"),
        Entry("Bulgarian", "bulgarian|bul", "BG", "🇧🇬"),
        Entry("Serbian", "serbian|srp", "SR", "🇷🇸"),
        Entry("Croatian", "croatian|hrv", "HR", "🇭🇷"),
        Entry("Slovenian", "slovenian|slv", "SL", "🇸🇮"),
        Entry("Dutch", "dutch|dut", "NL", "🇳🇱", listOf("🇳🇱", "🇧🇪")),
        Entry("Danish", "danish|dan", "DA", "🇩🇰"),
        Entry("Finnish", "finnish|fin", "FI", "🇫🇮"),
        Entry("Swedish", "swedish|swe", "SV", "🇸🇪"),
        Entry("Norwegian", "norwegian|nor", "NO", "🇳🇴"),
        Entry("Malay", "malay", "MS", "🇲🇾"),
        Entry("Latino", "latino|lat", "ES", "🇲🇽"),
    )

    private val byName: Map<String, Entry> = ALL.associateBy { it.name.lowercase(Locale.ROOT) }

    /** Each flag emoji's language; the first entry listing a flag owns it (so 🇮🇳 means Hindi). */
    private val byFlag: Map<String, Entry> = buildMap { ALL.forEach { entry -> entry.flags.forEach { putIfAbsent(it, entry) } } }

    /** ISO 639-1 / 639-2 codes (`en`, `eng`, `pt-br`) to language names, for addon `lang` fields. */
    private val byCode: Map<String, Entry> = buildMap {
        ALL.filter { it.code != null && it.name != "Latino" }.forEach { entry ->
            val code = entry.code.orEmpty().lowercase(Locale.ROOT)
            val key = if (entry.name == "Portuguese (Brazil)") "pt-br" else code
            putIfAbsent(key, entry)
            runCatching { Locale.forLanguageTag(code).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { putIfAbsent(it, entry) }
        }
    }

    fun entry(name: String): Entry? = byName[name.lowercase(Locale.ROOT)]

    fun emoji(name: String): String? = entry(name)?.emoji

    /** Upper-case ISO 639-1 code, or `null` (e.g. `Multi`). */
    fun code(name: String): String? = entry(name)?.code

    /** The language a flag emoji stands for, or `null`. */
    fun fromFlag(flag: String): String? = byFlag[flag]?.name

    /** A language name from an ISO 639-1/639-2 code such as `en`, `eng` or `pt-BR`; `null` when unknown. */
    fun fromCode(code: String): String? {
        val key = code.trim().lowercase(Locale.ROOT).replace('_', '-')
        return (byCode[key] ?: byCode[key.substringBefore('-')] ?: byCode[BIBLIOGRAPHIC[key]])?.name
    }

    /** Every flag emoji (a pair of regional indicator symbols) in [text], mapped to languages, without repeats. */
    fun fromFlags(text: String): List<String> =
        FLAG.findAll(text).mapNotNull { fromFlag(it.value) }.distinct().toList()

    private val FLAG = Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}")

    /** ISO 639-2/B codes some addons send, to their 639-2/T form Java knows. */
    private val BIBLIOGRAPHIC = mapOf("fre" to "fra", "ger" to "deu", "cze" to "ces", "slo" to "slk", "rum" to "ron", "dut" to "nld", "gre" to "ell", "chi" to "zho", "per" to "fas")
}
