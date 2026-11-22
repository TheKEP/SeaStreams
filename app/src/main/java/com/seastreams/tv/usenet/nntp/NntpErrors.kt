package com.seastreams.tv.usenet.nntp

import java.io.IOException
import java.io.InterruptedIOException

/** How the failover and pool layers treat an NNTP failure (ARCHITECTURE §10). */
enum class NntpErrorKind {
    /** 430 / 423 on this provider: content-level, the next provider may have it. */
    ARTICLE_NOT_FOUND,

    /** 480 / 481, or a 482 / 502 about the login or account: bad login, provider-level. */
    AUTH_FAILED,

    /** A refusal worded as "too many connections" (often 482 / 502): drives the AIMD throttle, never an auth failure. */
    CONNECTION_LIMIT,

    /** Socket, DNS, TLS or a dropped connection: transient. */
    CONNECTION,

    /** A dial, stall or segment deadline passed: transient. */
    TIMEOUT,

    /** An answer the client can't follow (desync, unexpected code). */
    PROTOCOL,

    /** No provider is configured, enabled or open. */
    NO_PROVIDERS,
}

/** Every engine failure. [code] is the NNTP status when one was received; [reply] is that line as the user may read it. */
open class NntpException(
    val kind: NntpErrorKind,
    message: String,
    val code: Int? = null,
    cause: Throwable? = null,
    val reply: String? = null,
) : IOException(message, cause) {
    /** Worth retrying later on the same provider. */
    val transient: Boolean
        get() = kind == NntpErrorKind.CONNECTION || kind == NntpErrorKind.TIMEOUT || kind == NntpErrorKind.CONNECTION_LIMIT
}

/** One provider answered 430/423 for [messageId]: recorded in the negative cache, then the next provider is asked. */
class ProviderMissingException(val messageId: String, provider: String, code: Int) :
    NntpException(NntpErrorKind.ARTICLE_NOT_FOUND, "$provider doesn't have the article", code)

/**
 * No provider supplied [messageId], and at least one actually answered 430. [allProviders] is true when every
 * provider tried (or remembered by the negative cache) answered 430, the definitive "this article is gone".
 */
class ArticleNotFoundException(val messageId: String, val allProviders: Boolean) :
    IOException(if (allProviders) "No provider has this article" else "The article is missing and other providers failed")

/** A queued or in-flight fetch was called off (seek, close). Not a failure of the release. */
class FetchCancelledException : IOException("The download was cancelled")

private val CREDENTIAL_TEXT = Regex(
    "invalid\\s+(?:user|username|login|password|credential)s?|bad\\s+(?:user|username|password|credential)s?|" +
        "incorrect\\s+(?:user|username|password)|wrong\\s+(?:user|username|password)|" +
        "authenticat(?:ion|e)\\s+(?:failed|rejected|invalid)|auth(?:entication)?\\s+failure",
    RegexOption.IGNORE_CASE,
)

private val LIMIT_TEXT = Regex(
    "too\\s+many\\s+(?:\\w+\\s+)?(?:connection|session|stream|login|client|user)|" +
        "(?:connection|session)s?\\s+limit|max(?:imum)?[^.]*(?:connection|session)|exceed[^.]*(?:connection|session)",
    RegexOption.IGNORE_CASE,
)

/** Words a refusal about the account (rather than the connection count) carries, as Highwinds' 502s do. */
private val ACCESS_TEXT = Regex(
    "auth|log\\s*-?\\s*in|password|passwd|user|denied|access|permission|credential|account|subscription|expired|forbidden",
    RegexOption.IGNORE_CASE,
)

/** A credential rejection recognised by its text, whatever the code. */
fun isCredentialRejectionText(text: String): Boolean = CREDENTIAL_TEXT.containsMatchIn(text)

/**
 * "Too many connections" by its text. A bare `482`/`502` is *not* one: Highwinds (Newshosting, Eweka, …) send
 * `502` for refused logins and blocked accounts too, so only the words decide.
 */
fun isConnectionLimitText(text: String): Boolean = LIMIT_TEXT.containsMatchIn(text)

/** A refusal about the login or account (`502 Access denied`, `482 Authentication …`). */
fun isAccessRefusalText(text: String): Boolean = ACCESS_TEXT.containsMatchIn(text)

/**
 * Classifies a failing status. A connection limit needs limit wording; a `482`/`502` that speaks of the login or
 * account is a bad login. During sign-in ([duringAuth]) any other 4xx or a `502` is a login failure too; mid-session
 * a 4xx is a protocol answer that fails only its own request, and an unexplained `502` is a refused connection.
 */
fun classifyStatus(code: Int, text: String, duringAuth: Boolean = false): NntpErrorKind = when {
    code == 430 || code == 423 -> NntpErrorKind.ARTICLE_NOT_FOUND
    isConnectionLimitText(text) -> NntpErrorKind.CONNECTION_LIMIT
    code == 480 || code == 481 || isCredentialRejectionText(text) -> NntpErrorKind.AUTH_FAILED
    (code == 482 || code == 502) && isAccessRefusalText(text) -> NntpErrorKind.AUTH_FAILED
    duringAuth && (code in 400..499 || code == 502) -> NntpErrorKind.AUTH_FAILED
    code == 400 || code == 502 -> NntpErrorKind.CONNECTION
    else -> NntpErrorKind.PROTOCOL
}

/** The server's reply as a user can read it in a toast: `502 Access denied`, control characters dropped, capped. */
fun NntpStatus.describe(): String {
    val clean = text.filter { it >= ' ' && it.code != 0x7f }.trim()
    val capped = if (clean.length > MAX_REPLY_CHARS) clean.take(MAX_REPLY_CHARS).trimEnd() + "…" else clean
    return if (capped.isEmpty()) code.toString() else "$code $capped"
}

private const val MAX_REPLY_CHARS = 160

/** The loader thread was interrupted (Media3 cancellation): restores the flag and reports it the way I/O code expects. */
internal fun interruptedIo(cause: InterruptedException): InterruptedIOException {
    Thread.currentThread().interrupt()
    return InterruptedIOException("Interrupted").apply { initCause(cause) }
}
