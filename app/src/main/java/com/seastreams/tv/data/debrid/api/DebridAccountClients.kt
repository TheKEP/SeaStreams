package com.seastreams.tv.data.debrid.api

import com.seastreams.tv.data.debrid.DebridServiceKind
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** What an account check keeps: premium status and expiry only. Usernames and emails are dropped while parsing. */
data class DebridAccount(val premium: Boolean, val premiumUntilMillis: Long?)

/**
 * One Debrid service (ARCHITECTURE §12): the account check, the library listing, file lists and link resolution.
 * Library-only (PRD §14): no client ever sends a hash or magnet, asks whether something is cached, or adds anything.
 * Throws [DebridAuthException], [DebridRateLimitedException], [DebridNotFoundException], [DebridServiceException],
 * or an `IOException` when the service can't be reached.
 */
interface DebridLibraryClient {
    val kind: DebridServiceKind

    suspend fun account(credential: String): DebridAccount

    /** The user's finished items, newest pages first as the service lists them, at most [MAX_LIBRARY_ITEMS]. */
    suspend fun library(credential: String): List<DebridItem>

    /** [item]'s files; returns [DebridItem.files] straight away when the listing already carried them. */
    suspend fun files(credential: String, item: DebridItem): List<DebridFile>

    /** A fresh `https://` playback link for one file; anything else is refused. */
    suspend fun resolve(credential: String, file: DebridFileHandle): String
}

/** Every service's client. */
@Singleton
class DebridClients internal constructor(private val clients: Map<DebridServiceKind, DebridLibraryClient>) {
    @Inject
    constructor(http: DebridHttp, endpoints: DebridEndpoints, clock: Clock) : this(
        listOf(
            RealDebridClient(http, endpoints),
            AllDebridClient(http, endpoints),
            TorBoxClient(http, endpoints),
            PremiumizeClient(http, endpoints, clock),
            DebridLinkClient(http, endpoints, clock),
            OffcloudClient(http, endpoints),
            PutIoClient(http, endpoints),
        ).associateBy { it.kind },
    )

    fun of(kind: DebridServiceKind): DebridLibraryClient = clients.getValue(kind)
}

/** AllDebrid's `data` object, or the matching exception for its `error.code`. */
internal fun allDebridData(json: JsonObject): JsonObject {
    if (json.str("status") == "success") return json.obj("data") ?: throw DebridServiceException()
    throw allDebridError(json.obj("error")?.str("code"))
}

/** The exception for an AllDebrid `error.code`. */
internal fun allDebridError(code: String?): DebridException = when (code) {
    "AUTH_MISSING_APIKEY", "AUTH_BAD_APIKEY", "AUTH_USER_BANNED", "AUTH_BLOCKED", "AUTH_MISSING_AGENT" -> DebridAuthException()
    "MUST_BE_PREMIUM" -> NotPremiumException()
    "TOO_MANY_REQUESTS", "RATE_LIMITED" -> DebridRateLimitedException()
    "LINK_DOWN", "LINK_IS_MISSING", "LINK_NOT_SUPPORTED", "MAGNET_INVALID_ID", "MAGNET_NOT_FOUND" -> DebridNotFoundException()
    else -> DebridServiceException()
}

/** A service that answers an account check with "not premium" instead of the account. */
class NotPremiumException : DebridException("Account isn't premium")

/** TorBox's `data`, or the matching exception for its `error`. */
internal fun torBoxData(json: JsonObject): Any? {
    if (json.bool("success") == true) return json["data"]
    when (json.str("error")) {
        "BAD_TOKEN", "AUTH_ERROR", "NO_AUTH", "UNAUTHORIZED" -> throw DebridAuthException()
        "RATE_LIMIT_EXCEEDED", "TOO_MANY_REQUESTS" -> throw DebridRateLimitedException()
        "ITEM_NOT_FOUND", "NOT_FOUND", "FILE_NOT_FOUND" -> throw DebridNotFoundException()
        else -> throw DebridServiceException()
    }
}

/** Debrid-Link's `value`, or the matching exception for its `error`. */
internal fun debridLinkValue(json: JsonObject): Any? {
    if (json.bool("success") == true) return json["value"]
    when (json.str("error")) {
        "badToken", "expired_token", "invalid_token", "unauthorized_client", "disabledAccount" -> throw DebridAuthException()
        "floodDetected" -> throw DebridRateLimitedException()
        "notFound", "torrentNotFound", "fileNotFound", "linkNotFound" -> throw DebridNotFoundException()
        else -> throw DebridServiceException()
    }
}

/** The body as a JSON object; anything else from a 2xx/4xx is an unexpected answer. */
internal fun DebridAnswer.requireJson(): JsonObject = json() ?: throw DebridServiceException()

/** AllDebrid requires an `agent` on every call (PRD §14). */
const val ALL_DEBRID_AGENT = "SeaStreams"

internal const val MILLIS_PER_SECOND = 1_000L
internal const val HTTP_BAD_REQUEST = 400
