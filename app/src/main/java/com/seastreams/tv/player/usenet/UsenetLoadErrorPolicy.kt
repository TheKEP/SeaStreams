package com.seastreams.tv.player.usenet

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.seastreams.tv.usenet.archive.RarFormatException
import com.seastreams.tv.usenet.archive.UsenetUnplayableException
import com.seastreams.tv.usenet.nntp.ArticleNotFoundException
import com.seastreams.tv.usenet.nntp.NntpErrorKind
import com.seastreams.tv.usenet.nntp.NntpException
import com.seastreams.tv.usenet.stream.SegmentLayoutException

/**
 * Retries for Usenet loads (ARCHITECTURE §10 ExoPlayer): lasting failures — a refused or broken release, an article
 * no provider has, a bad login, no providers, a layout that doesn't add up — fail at once, so the session's stream
 * failover moves on instead of ExoPlayer retrying for minutes. Timeouts and dropped connections retry as usual.
 */
class UsenetLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (isLasting(loadErrorInfo.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)

    companion object {
        fun isLasting(error: Throwable): Boolean {
            var cause: Throwable? = error
            var depth = 0
            while (cause != null && depth++ < MAX_DEPTH) {
                when {
                    cause is UsenetUnplayableException || cause is SegmentLayoutException || cause is RarFormatException -> return true
                    cause is ArticleNotFoundException -> return cause.allProviders
                    cause is NntpException && (cause.kind == NntpErrorKind.AUTH_FAILED || cause.kind == NntpErrorKind.NO_PROVIDERS) -> return true
                }
                cause = cause.cause
            }
            return false
        }

        private const val MAX_DEPTH = 8
    }
}
