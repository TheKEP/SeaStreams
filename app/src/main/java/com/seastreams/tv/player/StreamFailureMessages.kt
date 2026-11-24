package com.seastreams.tv.player

/**
 * Every stream-start failure text (PRD §9, §12, §14), in one place so the pre-player skip, the in-player gate and the
 * fallback timer read the same. Reasons come from [StreamFailureReason] (already brief and sanitized); `null` leaves
 * the reason out.
 */
object StreamFailureMessages {

    /** Failover on, moving past stream [from]: `Stream 3 didn't start (7z archive not supported). Trying stream 4 of 12…`. */
    fun advancing(from: Int, to: Int, total: Int, reason: FallbackReason, detail: String?): String {
        val what = if (reason == FallbackReason.TIMEOUT) "didn't start" else "failed"
        return "Stream ${from + 1} $what${detail.inParens()}. Trying stream ${to + 1} of $total…"
    }

    /** A refused Usenet release / Debrid file passed over before any player is given it. */
    fun skipped(from: Int, to: Int, total: Int, detail: String?): String = advancing(from, to, total, FallbackReason.TIMEOUT, detail)

    /** Failover on and no stream left: `None of the streams would play (last: password-protected archive)`. */
    fun exhausted(reason: FallbackReason, lastDetail: String?): String =
        if (reason == FallbackReason.TIMEOUT && lastDetail == null) NONE_IN_TIME else noneWouldPlay(lastDetail)

    fun noneWouldPlay(lastDetail: String?): String = NONE_WOULD_PLAY + lastDetail?.let { " (last: $it)" }.orEmpty()

    /** Failover off, the status pill over the Streams overlay. */
    fun stopped(index: Int, total: Int, detail: String?): String {
        val which = if (total > 1) "Stream ${index + 1} of $total" else "This stream"
        return "$which couldn't play${detail.inParens()}. Automatic failover is off — pick another stream."
    }

    private fun String?.inParens(): String = this?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()

    const val NONE_WOULD_PLAY = "None of the streams would play"
    const val NONE_IN_TIME = "None of the streams started in time"
}
