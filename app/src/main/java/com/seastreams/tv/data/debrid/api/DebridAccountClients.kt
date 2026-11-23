package com.seastreams.tv.data.debrid.api

import com.seastreams.tv.data.debrid.DebridServiceKind
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** What an account check keeps: premium status and expiry only. Usernames and emails are dropped while parsing. */
data class DebridAccount(val premium: Boolean, val premiumUntilMillis: Long?)

/**
 * One Debrid service (ARCHITECTURE §12). Milestone 11 only checks the account; Milestone 12 adds the library
 * listing, file lists and link resolution. Throws [DebridAuthException], [DebridRateLimitedException],
 * [DebridServiceException], or an `IOException` when the service can't be reached.
 */
interface DebridLibraryClient {
    val kind: DebridServiceKind

    suspend fun account(credential: String): DebridAccount
}

/** Every service's client. */
@Singleton
class DebridClients @Inject constructor(http: DebridHttp, endpoints: DebridEndpoints, clock: Clock) {
    private val clients: Map<DebridServiceKind, DebridLibraryClient> = listOf(
        RealDebridClient(http, endpoints),
        AllDebridClient(http, endpoints),
        TorBoxClient(http, endpoints),
        PremiumizeClient(http, endpoints, clock),
        DebridLinkClient(http, endpoints, clock),
        OffcloudClient(http, endpoints),
        PutIoClient(http, endpoints),
    ).associateBy { it.kind }

    fun of(kind: DebridServiceKind): DebridLibraryClient = clients.getValue(kind)
}

/** `GET /user`: `type` = `premium` / `free`, `expiration` ISO-8601. */
class RealDebridClient(private val http: DebridHttp, private val endpoints: DebridEndpoints) : DebridLibraryClient {
    override val kind = DebridServiceKind.REAL_DEBRID

    override suspend fun account(credential: String): DebridAccount {
        val json = http.get("${endpoints.realDebridApi}/user", credential).throwOnHttpError().requireJson()
        if (json.long("id") == null) throw DebridServiceException()
        val premium = json.str("type") == "premium"
        return DebridAccount(premium, if (premium) parseTimeMillis(json.str("expiration")) else null)
    }
}

/** `GET v4/user`: `status` + `data.user.isPremium` / `premiumUntil` (epoch seconds); errors are HTTP 200 + `error.code`. */
class AllDebridClient(private val http: DebridHttp, private val endpoints: DebridEndpoints) : DebridLibraryClient {
    override val kind = DebridServiceKind.ALL_DEBRID

    override suspend fun account(credential: String): DebridAccount {
        val json = http.get("${endpoints.allDebrid}/v4/user?agent=$ALL_DEBRID_AGENT", credential).throwOnHttpError().requireJson()
        val user = allDebridData(json).obj("user") ?: throw DebridServiceException()
        val premium = user.bool("isPremium") == true
        return DebridAccount(premium, if (premium) user.long("premiumUntil")?.let(::epochToMillis) else null)
    }
}

/** AllDebrid's `data` object, or the matching exception for its `error.code`. */
internal fun allDebridData(json: JsonObject): JsonObject {
    if (json.str("status") == "success") return json.obj("data") ?: throw DebridServiceException()
    when (json.obj("error")?.str("code")) {
        "AUTH_MISSING_APIKEY", "AUTH_BAD_APIKEY", "AUTH_USER_BANNED", "AUTH_BLOCKED", "AUTH_MISSING_AGENT" -> throw DebridAuthException()
        "MUST_BE_PREMIUM" -> throw NotPremiumException()
        "TOO_MANY_REQUESTS", "RATE_LIMITED" -> throw DebridRateLimitedException()
        else -> throw DebridServiceException()
    }
}

/** A service that answers an account check with "not premium" instead of the account. */
class NotPremiumException : DebridException("Account isn't premium")

/** `GET user/me`: `success` + `data.plan` (0 = free) / `premium_expires_at`. */
class TorBoxClient(private val http: DebridHttp, private val endpoints: DebridEndpoints) : DebridLibraryClient {
    override val kind = DebridServiceKind.TORBOX

    override suspend fun account(credential: String): DebridAccount {
        val json = http.get("${endpoints.torBox}/user/me", credential).throwOnHttpError().requireJson()
        val data = torBoxData(json) as? JsonObject ?: throw DebridServiceException()
        val premium = (data.long("plan") ?: 0L) > 0
        return DebridAccount(premium, if (premium) parseTimeMillis(data.str("premium_expires_at")) else null)
    }
}

/** TorBox's `data`, or the matching exception for its `error`. */
internal fun torBoxData(json: JsonObject): Any? {
    if (json.bool("success") == true) return json["data"]
    when (json.str("error")) {
        "BAD_TOKEN", "AUTH_ERROR", "NO_AUTH", "UNAUTHORIZED" -> throw DebridAuthException()
        "RATE_LIMIT_EXCEEDED", "TOO_MANY_REQUESTS" -> throw DebridRateLimitedException()
        else -> throw DebridServiceException()
    }
}

/** `GET account/info?apikey=`: `status` + `premium_until` (epoch seconds, or `false`). Premiumize only takes the key in the query. */
class PremiumizeClient(
    private val http: DebridHttp,
    private val endpoints: DebridEndpoints,
    private val clock: Clock,
) : DebridLibraryClient {
    override val kind = DebridServiceKind.PREMIUMIZE

    override suspend fun account(credential: String): DebridAccount {
        val url = "${endpoints.premiumize}/account/info?apikey=" + URLEncoder.encode(credential, Charsets.UTF_8.name())
        val json = http.get(url).throwOnHttpError().requireJson()
        if (json.str("status") != "success") throw DebridAuthException()
        val until = json.long("premium_until")?.let(::epochToMillis)
        val premium = until != null && until > clock.millis()
        return DebridAccount(premium, if (premium) until else null)
    }
}

/** `GET v2/account/infos`: `success` + `value.accountType` (0 = free) / `premiumLeft` (seconds). */
class DebridLinkClient(
    private val http: DebridHttp,
    private val endpoints: DebridEndpoints,
    private val clock: Clock,
) : DebridLibraryClient {
    override val kind = DebridServiceKind.DEBRID_LINK

    override suspend fun account(credential: String): DebridAccount {
        val json = http.get("${endpoints.debridLink}/v2/account/infos", credential).throwOnHttpError().requireJson()
        if (json.bool("success") != true) {
            when (json.str("error")) {
                "badToken", "expired_token", "invalid_token", "unauthorized_client", "disabledAccount" -> throw DebridAuthException()
                "floodDetected" -> throw DebridRateLimitedException()
                else -> throw DebridServiceException()
            }
        }
        val value = json.obj("value") ?: throw DebridServiceException()
        val left = value.long("premiumLeft") ?: 0L
        val premium = (value.long("accountType") ?: 0L) > 0 && left > 0
        return DebridAccount(premium, if (premium) clock.millis() + left * MILLIS_PER_SECOND else null)
    }
}

/** `GET account/info` (Bearer): a `user_id` means the key works; premium / expiry are read when present. */
class OffcloudClient(private val http: DebridHttp, private val endpoints: DebridEndpoints) : DebridLibraryClient {
    override val kind = DebridServiceKind.OFFCLOUD

    override suspend fun account(credential: String): DebridAccount {
        val json = http.get("${endpoints.offcloud}/account/info", credential).throwOnHttpError().requireJson()
        if (json.str("error") != null) throw DebridAuthException()
        if (json.str("user_id") == null && json.str("userId") == null) throw DebridServiceException()
        val premium = json.bool("isPremium") ?: json.bool("is_premium") ?: true
        val until = parseTimeMillis(json.str("expirationDate") ?: json.str("expiration_date"))
        return DebridAccount(premium, if (premium) until else null)
    }
}

/** `GET account/info` (Bearer): `info.account_active` / `plan_expiration_date`. */
class PutIoClient(private val http: DebridHttp, private val endpoints: DebridEndpoints) : DebridLibraryClient {
    override val kind = DebridServiceKind.PUT_IO

    override suspend fun account(credential: String): DebridAccount {
        val answer = http.get("${endpoints.putIo}/account/info", credential)
        if (answer.code == HTTP_BAD_REQUEST) throw DebridAuthException()
        val json = answer.throwOnHttpError().requireJson()
        val info = json.obj("info")
        if (json.str("status") != "OK" || info == null) throw DebridAuthException()
        val premium = info.bool("account_active") == true
        return DebridAccount(premium, if (premium) parseTimeMillis(info.str("plan_expiration_date")) else null)
    }
}

/** The body as a JSON object; anything else from a 2xx/4xx is an unexpected answer. */
internal fun DebridAnswer.requireJson(): JsonObject = json() ?: throw DebridServiceException()

/** AllDebrid requires an `agent` on every call (PRD §14). */
const val ALL_DEBRID_AGENT = "SeaStreams"

private const val MILLIS_PER_SECOND = 1_000L
private const val HTTP_BAD_REQUEST = 400
