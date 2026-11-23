package com.seastreams.tv.domain.streams.format

import com.seastreams.tv.domain.model.StreamDisplay
import com.seastreams.tv.domain.model.StreamHints
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.streams.ContentKind
import com.seastreams.tv.domain.streams.parse.StreamFacts

/**
 * The formatter preview shared by the TV `Stream formatter` panel and the portal's `/api/streams/format-preview`: the
 * release shown in `aio-formatter.png` (63 GB, 2h32m runtime, 10 days old, from Torrentio) rendered with any templates.
 */
object FormatterPreview {

    const val SAMPLE_FILENAME = "Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP.mkv"

    val SAMPLE: StreamSource = StreamSource(
        addonUrl = "preview://torrentio",
        addonName = "Torrentio",
        url = "preview://stream",
        label = "Torrentio\n4k",
        description = "$SAMPLE_FILENAME\n👤 128 💾 63 GB ⚙️ ThePirateBay",
        headers = emptyMap(),
        hints = StreamHints(filename = SAMPLE_FILENAME, sizeBytes = 63_000_000_000L),
    )

    /** [templates] applied to the sample, or to [filename] when given (the portal lets people try their own). */
    fun render(templates: FormatterTemplates, nowMillis: Long, filename: String? = null): StreamDisplay {
        val name = filename?.trim()?.takeIf { it.isNotEmpty() } ?: SAMPLE_FILENAME
        val stream = SAMPLE.copy(hints = SAMPLE.hints.copy(filename = name, publishedMillis = nowMillis - AGE_MS))
        val parsed = StreamFacts.parse(stream, RUNTIME_MS, nowMillis, usenetAddonUrl = "")
        return StreamFormatter(templates).format(FormatInput(parsed, stream.addonName, stream.addonUrl, ContentKind.MOVIE))
    }

    private const val RUNTIME_MS = 152 * 60_000L
    private const val AGE_MS = 10 * 24 * 3_600_000L
}
