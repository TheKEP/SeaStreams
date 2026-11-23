package com.seastreams.tv.domain.streams

import com.seastreams.tv.domain.model.StreamHints
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.streams.filter.StreamCandidate
import com.seastreams.tv.domain.streams.parse.StreamFacts

internal const val USENET_URL = "usenet://builtin"

/** An addon stream whose description is [filename] plus optional extra lines. */
internal fun stream(
    filename: String,
    addon: String = "a",
    extra: String = "",
    url: String = "https://cdn/$addon/$filename",
    sizeBytes: Long? = null,
    label: String = addon,
) = StreamSource(
    addonUrl = "https://$addon/manifest.json",
    addonName = addon,
    url = url,
    label = label,
    description = if (extra.isEmpty()) filename else "$filename\n$extra",
    headers = emptyMap(),
    hints = StreamHints(filename = filename, sizeBytes = sizeBytes),
)

internal fun candidate(source: StreamSource, runtimeMinutes: Int? = null) =
    StreamCandidate(source, StreamFacts.parse(source, runtimeMinutes?.let { it * 60_000L }, nowMillis = 0, usenetAddonUrl = USENET_URL))

internal const val GB = 1_000_000_000L
