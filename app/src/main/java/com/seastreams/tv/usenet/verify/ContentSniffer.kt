package com.seastreams.tv.usenet.verify

/** The container a media file's first bytes identify. */
enum class MediaContainer(val extension: String, val mimeType: String) {
    MATROSKA("mkv", "video/x-matroska"),
    WEBM("webm", "video/webm"),
    MP4("mp4", "video/mp4"),
    AVI("avi", "video/x-msvideo"),
    MPEG_TS("ts", "video/mp2t"),
    MPEG_PS("mpg", "video/mpeg"),
    ASF("wmv", "video/x-ms-wmv"),
    FLV("flv", "video/x-flv"),
    UNKNOWN("bin", "application/octet-stream"),
}

/**
 * *Verify file contents* (PRD §12): what the first bytes of the selected file are. A release whose "video" is
 * none of these (an executable, an archive in disguise, random bytes) is refused before playback starts.
 * Also tells [MatroskaHoleRepair] whether holes may be written as EBML Void elements.
 */
object ContentSniffer {
    const val HEAD_BYTES = 4 * 1024
    private const val TS_PACKET = 188

    fun sniff(head: ByteArray): MediaContainer = when {
        head.startsWith(0x1A, 0x45, 0xDF, 0xA3) -> if (docType(head) == "webm") MediaContainer.WEBM else MediaContainer.MATROSKA
        head.size >= 12 && head.matches(4, 'f', 't', 'y', 'p') -> MediaContainer.MP4
        head.size >= 12 && head.matches(4, 'm', 'o', 'o', 'v') -> MediaContainer.MP4
        head.size >= 12 && head.matches(0, 'R', 'I', 'F', 'F') && head.matches(8, 'A', 'V', 'I', ' ') -> MediaContainer.AVI
        head.startsWith(0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11) -> MediaContainer.ASF
        head.matches(0, 'F', 'L', 'V') -> MediaContainer.FLV
        head.startsWith(0x00, 0x00, 0x01, 0xBA) -> MediaContainer.MPEG_PS
        isTransportStream(head, 0) || isTransportStream(head, 4) -> MediaContainer.MPEG_TS
        else -> MediaContainer.UNKNOWN
    }

    /** By name when the bytes aren't known yet. */
    fun byName(name: String): MediaContainer? = when (name.substringAfterLast('.', "").lowercase()) {
        "mkv", "mk3d", "mka" -> MediaContainer.MATROSKA
        "webm" -> MediaContainer.WEBM
        "mp4", "m4v", "mov" -> MediaContainer.MP4
        "avi", "divx" -> MediaContainer.AVI
        "ts", "m2ts", "mts" -> MediaContainer.MPEG_TS
        "mpg", "mpeg", "vob" -> MediaContainer.MPEG_PS
        "wmv" -> MediaContainer.ASF
        "flv" -> MediaContainer.FLV
        else -> null
    }

    /** 188-byte packets (or 192-byte M2TS with a 4-byte prefix): three sync bytes in a row. */
    private fun isTransportStream(head: ByteArray, offset: Int): Boolean {
        val stride = TS_PACKET + offset
        return head.size >= offset + 2 * stride + 1 && (0..2).all { head[offset + it * stride] == 0x47.toByte() }
    }

    /** The EBML `DocType` string (`matroska` / `webm`) when it is in the head. */
    private fun docType(head: ByteArray): String? {
        for (i in 0 until minOf(head.size - 3, 64)) {
            if (head[i] == 0x42.toByte() && head[i + 1] == 0x82.toByte()) {
                val length = head[i + 2].toInt() and 0x7F
                if (head[i + 2].toInt() and 0x80 == 0 || i + 3 + length > head.size) return null
                return String(head, i + 3, length, Charsets.US_ASCII)
            }
        }
        return null
    }

    private fun ByteArray.startsWith(vararg values: Int): Boolean =
        size >= values.size && values.indices.all { this[it] == values[it].toByte() }

    private fun ByteArray.matches(at: Int, vararg chars: Char): Boolean =
        size >= at + chars.size && chars.indices.all { this[at + it] == chars[it].code.toByte() }
}
