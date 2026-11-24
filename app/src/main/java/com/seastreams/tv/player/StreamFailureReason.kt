package com.seastreams.tv.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import com.seastreams.tv.data.debrid.api.DebridException
import com.seastreams.tv.usenet.archive.UsenetUnplayableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Brief, human-readable reasons a stream couldn't start (PRD §9, §12, §14), for the failover status, the failover-off
 * status pill and the "none would play" toast: `password-protected archive`, `HTTP 403`, `unsupported video codec (HEVC)`.
 *
 * Known preflight / loader messages map to fixed wording. Anything else goes through [sanitize] first, so a signed URL,
 * API key, token or account detail inside an exception message never reaches the screen (ARCHITECTURE §8).
 */
object StreamFailureReason {

    /** A [com.seastreams.tv.player.usenet.PreflightResult.Skip] reason (Usenet / Debrid preflight). */
    fun fromPreflight(message: String): String = classify(message) ?: sanitize(message).ifEmpty { GENERIC }

    /** A failure thrown while opening or handing off a stream (e.g. the external-player `prepare`). */
    fun fromThrowable(error: Throwable): String {
        if (error is PlaybackException) return fromPlayback(error)
        return fromCauses(error) ?: GENERIC
    }

    /** An ExoPlayer error: the domain cause first (archive, Debrid, HTTP status), then the Media3 error code. */
    fun fromPlayback(error: PlaybackException): String = fromCauses(error.cause) ?: fromErrorCode(error) ?: GENERIC

    private fun fromCauses(start: Throwable?): String? {
        var cause = start
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            when (cause) {
                is HttpDataSource.InvalidResponseCodeException -> return httpStatus(cause.responseCode)
                is SocketTimeoutException -> return TIMED_OUT
                is UnknownHostException -> return SERVER_NOT_FOUND
                is SSLException -> return SECURE_FAILED
                is UsenetUnplayableException, is DebridException -> return fromPreflight(cause.message.orEmpty())
            }
            cause.message?.let(::classify)?.let { return it }
            cause = cause.cause
            depth++
        }
        return null
    }

    private fun fromErrorCode(error: PlaybackException): String? = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> TIMED_OUT
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "connection failed"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "HTTP error"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "file not found"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "access denied"
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "insecure HTTP link refused"
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "not a video file"
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "file is shorter than expected"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "unsupported container"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "unsupported stream format"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        -> "damaged or incomplete file"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        -> unsupportedCodec(error)
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        -> "audio output failed"
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "live stream fell behind"
        in DRM_CODES -> "DRM-protected stream"
        else -> null
    }

    private fun unsupportedCodec(error: PlaybackException): String {
        val mime = (error as? ExoPlaybackException)?.rendererFormat?.sampleMimeType
        val kind = when {
            MimeTypes.isAudio(mime) -> "audio"
            MimeTypes.isVideo(mime) -> "video"
            else -> return "unsupported codec"
        }
        val name = PlayerTracks.codecName(mime)
        return "unsupported $kind codec" + name?.let { " ($it)" }.orEmpty()
    }

    /** Fixed wording for the messages the Usenet, Debrid and HTTP code raise; `null` when none matches. */
    internal fun classify(message: String): String? {
        // Matched on the secret-free text, so a word inside a URL or token can never pick the reason.
        val clean = strip(message)
        val text = clean.lowercase()
        val sevenZip = SEVEN_ZIP.containsMatchIn(text)
        return when {
            "password is wrong" in text -> "wrong archive password"
            sevenZip && "compressed" in text -> "compressed 7z not supported"
            sevenZip && "damaged" in text -> "damaged archive"
            sevenZip -> "7z archive not supported"
            "zip archive" in text -> "zip archive not supported"
            "password-protected" in text || "encrypted" in text -> "password-protected archive"
            "compressed" in text -> "compressed RAR not supported"
            "too many to repair" in text || "missing from the release" in text || "articles are missing" in text ||
                "article is missing" in text || "start of a file is missing" in text -> "missing articles"
            "volume" in text && "missing" in text -> "archive volume missing"
            "volume" in text && ("damaged" in text || "belong" in text || "add up" in text) -> "damaged archive"
            "no video" in text || "isn't a video" in text || "lists no files" in text || "only a sample" in text -> "no video in release"
            "nzb is too large" in text -> "NZB too large"
            "expired" in text -> "result expired"
            "usenet provider" in text -> "no Usenet provider set up"
            "usenet is switched off" in text -> "Usenet is off"
            "debrid is switched off" in text -> "Debrid is off"
            "isn't listed any more" in text || ("account isn't enabled" in text) -> "file not found on Debrid"
            "isn't premium" in text -> "Debrid account not premium"
            "invalid api key" in text || "sign-in" in text -> "API key or sign-in rejected"
            "rate limited" in text -> "rate limited"
            "secure playback link" in text -> "no secure playback link"
            "timed out" in text || "timeout" in text -> TIMED_OUT
            "unable to resolve host" in text -> SERVER_NOT_FOUND
            else -> HTTP_CODE.find(clean)?.groupValues?.get(1)?.toIntOrNull()?.let(::httpStatus)
        }
    }

    private fun httpStatus(code: Int): String = when (code) {
        HTTP_FORBIDDEN -> "HTTP 403 (access denied)"
        HTTP_NOT_FOUND -> "HTTP 404 (not found)"
        HTTP_GONE -> "HTTP 410 (link expired)"
        HTTP_TOO_MANY -> "HTTP 429 (rate limited)"
        else -> "HTTP $code"
    }

    /**
     * Strips anything secret-looking from free text: URLs, `key=value` credentials, long hex/base64 runs (tokens,
     * hashes); then collapses whitespace and caps the length. Lower-cases the first letter so it reads inside `(…)`.
     */
    fun sanitize(text: String): String {
        val cleaned = strip(text)
        val capped = if (cleaned.length > MAX_LENGTH) cleaned.take(MAX_LENGTH - 1).trimEnd() + "…" else cleaned
        return capped.replaceFirstChar { if (it.isUpperCase() && capped.getOrNull(1)?.isLowerCase() != false) it.lowercaseChar() else it }
    }

    private fun strip(text: String): String = text
        .replace(URL, HIDDEN)
        .replace(SECRET_PARAM) { "${it.groupValues[1]}=$HIDDEN" }
        .replace(LONG_TOKEN, HIDDEN)
        .replace(WHITESPACE, " ")
        .trim()
        .trimEnd('.', ':', ';', ',', ' ')

    const val GENERIC = "playback error"
    private const val TIMED_OUT = "connection timed out"
    private const val SERVER_NOT_FOUND = "server not found"
    private const val SECURE_FAILED = "secure connection failed"
    private const val HIDDEN = "[hidden]"
    private const val MAX_LENGTH = 60
    private const val MAX_CAUSE_DEPTH = 8
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_GONE = 410
    private const val HTTP_TOO_MANY = 429
    private val DRM_CODES = PlaybackException.ERROR_CODE_DRM_UNSPECIFIED until PlaybackException.ERROR_CODE_DRM_UNSPECIFIED + 1_000
    private val URL = Regex("""\b[a-zA-Z][a-zA-Z0-9+.\-]*://\S+""")
    private val SECRET_PARAM = Regex(
        """\b(token|access_token|apikey|api_key|key|auth|authorization|sig|signature|password|pass|passwd|user|username|session)\s*[=:]\s*\S+""",
        RegexOption.IGNORE_CASE,
    )
    private val LONG_TOKEN = Regex("""[A-Za-z0-9+/_\-]{20,}={0,2}""")
    private val SEVEN_ZIP = Regex("""\b7z\b""")
    private val WHITESPACE = Regex("""\s+""")
    private val HTTP_CODE = Regex("""\b(?:HTTP|response code:?)\s*([1-5]\d\d)\b""", RegexOption.IGNORE_CASE)
}
