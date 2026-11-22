package com.seastreams.tv.usenet.nzb

/**
 * Best-effort filename and part number from an NZB `subject` (subjects are not standardised):
 * - `[1/8] - "My.File.mkv" yEnc (1/120)` → the quoted token;
 * - `My Release [01/42] - file.part01.rar yEnc (1/200) 524288000` → SABnzbd's filename pattern;
 * - otherwise a token ending in a known extension.
 */
object SubjectNames {
    private val QUOTED = Regex("\"([^\"]+)\"")
    private val YENC_MARKER = Regex("\\byenc\\b", RegexOption.IGNORE_CASE)
    private val COUNTER = Regex("[(\\[]\\s*\\d+\\s*/\\s*\\d+\\s*[)\\]]")
    private val TRAILING_SIZE = Regex("\\b\\d{4,}\\b\\s*$")
    private val SABNZBD_LIKE = Regex("\\b([\\w\\-+()' .,]+(?:\\[[\\w\\-/+()' .,]*][\\w\\-+()' .,]*)*\\.[A-Za-z0-9]{2,4})\\b")
    private val FILENAME_LIKE = Regex(
        "([^\\s\"/\\\\]+\\.(?:mkv|mp4|avi|wmv|mov|m4v|ts|m2ts|flv|webm|mpg|mpeg|iso|img|rar|r\\d{2}|zip|7z|tar|gz|nfo|" +
            "sfv|par2|nzb|srt|sub|idx|ass|mka|mp3|flac|ogg|wav|\\d{3}))\\b",
        RegexOption.IGNORE_CASE,
    )
    private val PART_COUNTER = Regex("[(\\[]\\s*(\\d+)\\s*/\\s*\\d+\\s*[)\\]]")

    fun filename(subject: String): String? {
        if (subject.isBlank()) return null
        QUOTED.find(subject)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val cleaned = subject.replace(YENC_MARKER, " ").replace(COUNTER, " ").replace(TRAILING_SIZE, " ").trim()
        SABNZBD_LIKE.find(cleaned)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return FILENAME_LIKE.find(cleaned)?.groupValues?.get(1)?.trim()
    }

    /** The last `(n/m)` counter's `n`: the article counter comes after the file counter in most subjects. */
    fun partNumber(subject: String): Int? = PART_COUNTER.findAll(subject).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
}
