package com.seastreams.tv.player.subtitle

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * Downloads and parses an addon subtitle file (SRT, WebVTT or SSA/ASS, optionally gzipped) into an
 * [ExternalSubtitleTimeline]. External tracks are timed by SeaStreams itself rather than ExoPlayer's
 * text renderer, which is what makes the live `- 0.0s +` sync offset possible.
 */
class ExternalSubtitleLoader(private val client: OkHttpClient) {

    private val parsers = DefaultSubtitleParserFactory()

    suspend fun load(url: String): ExternalSubtitleTimeline = withContext(Dispatchers.IO) {
        val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("subtitle download failed: ${response.code}")
            response.body.bytes()
        }
        parse(unzipIfNeeded(bytes))
    }

    private fun parse(bytes: ByteArray): ExternalSubtitleTimeline {
        val format = Format.Builder().setSampleMimeType(detectMimeType(bytes)).build()
        val parser = parsers.create(format)
        val cues = ArrayList<TimedCue>()
        parser.parse(bytes, SubtitleParser.OutputOptions.allCues()) { timed ->
            val text = timed.cues.mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }.joinToString("\n")
            val end = when {
                timed.endTimeUs != C.TIME_UNSET -> timed.endTimeUs
                timed.durationUs != C.TIME_UNSET -> timed.startTimeUs + timed.durationUs
                else -> timed.startTimeUs + DEFAULT_CUE_US
            }
            if (text.isNotEmpty()) cues.add(TimedCue(timed.startTimeUs, end, text))
        }
        if (cues.isEmpty()) throw IOException("subtitle file had no cues")
        return ExternalSubtitleTimeline(cues)
    }

    companion object {
        private const val DEFAULT_CUE_US = 3_000_000L
        private const val SNIFF_BYTES = 512

        /** Sniffs the subtitle format from the file's first bytes (addon URLs rarely carry an extension). */
        fun detectMimeType(bytes: ByteArray): String {
            val head = String(bytes, 0, minOf(bytes.size, SNIFF_BYTES), Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
            return when {
                head.startsWith("WEBVTT") -> MimeTypes.TEXT_VTT
                head.contains("[Script Info]", ignoreCase = true) -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
        }

        fun unzipIfNeeded(bytes: ByteArray): ByteArray {
            val gzip = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
            return if (gzip) GZIPInputStream(bytes.inputStream()).use { it.readBytes() } else bytes
        }
    }
}
