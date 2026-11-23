package com.seastreams.tv.data.debrid.auth

import com.seastreams.tv.data.debrid.DebridClientIds
import com.seastreams.tv.data.debrid.DebridCredentials
import com.seastreams.tv.data.debrid.DebridServiceKind
import com.seastreams.tv.data.debrid.api.ALL_DEBRID_AGENT
import com.seastreams.tv.data.debrid.api.DebridAnswer
import com.seastreams.tv.data.debrid.api.DebridAuthException
import com.seastreams.tv.data.debrid.api.DebridEndpoints
import com.seastreams.tv.data.debrid.api.DebridHttp
import com.seastreams.tv.data.debrid.api.DebridRateLimitedException
import com.seastreams.tv.data.debrid.api.DebridServiceException
import com.seastreams.tv.data.debrid.api.allDebridData
import com.seastreams.tv.data.debrid.api.bool
import com.seastreams.tv.data.debrid.api.long
import com.seastreams.tv.data.debrid.api.obj
import com.seastreams.tv.data.debrid.api.requireJson
import com.seastreams.tv.data.debrid.api.str
import com.seastreams.tv.data.debrid.api.torBoxData
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** Every available code sign-in and token refresher (Debrid-Link / put.io only once their client ID is configured). */
@Singleton
class DeviceFlows @Inject constructor(http: DebridHttp, endpoints: DebridEndpoints, clientIds: DebridClientIds) :
    TokenRefreshers, DeviceFlowProvider {
    private val realDebrid = RealDebridDeviceFlow(http, endpoints)
    private val debridLink = clientIds.debridLink.takeIf { it.isNotBlank() }?.let { DebridLinkDeviceFlow(http, endpoints, it) }
    private val flows: Map<DebridServiceKind, DeviceFlow> = listOfNotNull(
        realDebrid,
        AllDebridPinFlow(http, endpoints),
        TorBoxDeviceFlow(http, endpoints),
        debridLink,
        clientIds.putIo.takeIf { it.isNotBlank() }?.let { PutIoCodeFlow(http, endpoints, it) },
    ).associateBy { it.kind }

    /** The code sign-in [kind] offers right now, if any. */
    override fun flow(kind: DebridServiceKind): DeviceFlow? = flows[kind]

    override fun of(kind: DebridServiceKind): TokenRefresh? = when (kind) {
        DebridServiceKind.REAL_DEBRID -> realDebrid
        DebridServiceKind.DEBRID_LINK -> debridLink
        else -> null
    }
}

/**
 * Real-Debrid's device flow for open-source apps (ARCHITECTURE §12): `device/code` with the public client ID and
 * `new_credentials=yes` → poll `device/credentials` until the user approves, which yields a per-user client ID and
 * secret → `token` exchanges them (with the device code) for an access + refresh token. Tokens are refreshed through
 * the same `token` call with the refresh token as the code.
 */
class RealDebridDeviceFlow(private val http: DebridHttp, private val endpoints: DebridEndpoints) : DeviceFlow, TokenRefresh {
    override val kind = DebridServiceKind.REAL_DEBRID

    override suspend fun start(): DeviceCode {
        val json = http.get("${endpoints.realDebridOauth}/device/code?client_id=$OPEN_SOURCE_CLIENT_ID&new_credentials=yes")
            .throwOnHttpError().requireJson()
        return deviceCode(json, "device_code", "user_code", "verification_url")
    }

    override suspend fun poll(code: DeviceCode): DevicePoll {
        val answer = http.get("${endpoints.realDebridOauth}/device/credentials?client_id=$OPEN_SOURCE_CLIENT_ID&code=${enc(code.deviceCode)}")
        val json = answer.json()
        val clientId = json?.str("client_id")
        val clientSecret = json?.str("client_secret")
        if (!answer.ok || clientId == null || clientSecret == null) return pendingOr(answer, json?.str("error"))
        val tokens = exchange(clientId, clientSecret, code.deviceCode)
        return DevicePoll.Linked(DeviceTokens(tokens.accessToken, tokens.refreshToken, clientId, clientSecret, tokens.expiresInSeconds))
    }

    override suspend fun refresh(credentials: DebridCredentials): DeviceTokens {
        val clientId = credentials.clientId ?: throw DebridAuthException("Sign-in needed")
        val secret = credentials.clientSecret ?: throw DebridAuthException("Sign-in needed")
        val refresh = credentials.refreshToken ?: throw DebridAuthException("Sign-in needed")
        return exchange(clientId, secret, refresh)
    }

    private suspend fun exchange(clientId: String, secret: String, code: String): DeviceTokens {
        val answer = http.postForm(
            "${endpoints.realDebridOauth}/token",
            mapOf("client_id" to clientId, "client_secret" to secret, "code" to code, "grant_type" to DEVICE_GRANT),
        )
        if (answer.code == HTTP_BAD_REQUEST) throw DebridAuthException("Sign-in needed")
        return tokens(answer.throwOnHttpError().requireJson())
    }

    private companion object {
        /** Real-Debrid's published client ID for open-source apps. */
        const val OPEN_SOURCE_CLIENT_ID = "X245A4XAIBGVM"
    }
}

/** AllDebrid's PIN sign-in: `v4.1/pin/get` → poll `v4/pin/check` (pin + check hash) until `activated` → API key. */
class AllDebridPinFlow(private val http: DebridHttp, private val endpoints: DebridEndpoints) : DeviceFlow {
    override val kind = DebridServiceKind.ALL_DEBRID

    override suspend fun start(): DeviceCode {
        val data = allDebridData(http.get("${endpoints.allDebrid}/v4.1/pin/get?agent=$ALL_DEBRID_AGENT").throwOnHttpError().requireJson())
        val pin = data.str("pin") ?: throw DebridServiceException()
        val check = data.str("check") ?: throw DebridServiceException()
        val url = data.str("user_url") ?: data.str("base_url") ?: throw DebridServiceException()
        return DeviceCode(check, pin, requireHttps(url), DEFAULT_INTERVAL_SECONDS, (data.long("expires_in") ?: DEFAULT_EXPIRY_SECONDS).toInt())
    }

    override suspend fun poll(code: DeviceCode): DevicePoll {
        val json = http.postForm(
            "${endpoints.allDebrid}/v4/pin/check?agent=$ALL_DEBRID_AGENT",
            mapOf("pin" to code.userCode, "check" to code.deviceCode),
        ).throwOnHttpError().requireJson()
        if (json.str("status") != "success") {
            return when (json.obj("error")?.str("code")) {
                "PIN_EXPIRED", "PIN_INVALID", "PIN_ALREADY_AUTHED" -> DevicePoll.Expired
                "PIN_DENIED" -> DevicePoll.Denied
                else -> throw DebridServiceException()
            }
        }
        val data = json.obj("data") ?: throw DebridServiceException()
        val key = data.str("apikey")
        return if (data.bool("activated") == true && key != null) DevicePoll.Linked(DeviceTokens(key)) else DevicePoll.Pending
    }
}

/** TorBox's device sign-in (as in Nabrio `TorBoxApi`): `user/auth/device/start` → poll `user/auth/device/token`. */
class TorBoxDeviceFlow(private val http: DebridHttp, private val endpoints: DebridEndpoints) : DeviceFlow {
    override val kind = DebridServiceKind.TORBOX

    override suspend fun start(): DeviceCode {
        val json = http.get("${endpoints.torBox}/user/auth/device/start?app=$ALL_DEBRID_AGENT").throwOnHttpError().requireJson()
        val data = torBoxData(json) as? JsonObject ?: throw DebridServiceException()
        val userCode = data.str("code") ?: data.str("user_code") ?: throw DebridServiceException()
        val url = data.str("verification_url") ?: data.str("friendly_verification_url") ?: throw DebridServiceException()
        return DeviceCode(
            data.str("device_code") ?: throw DebridServiceException(),
            userCode,
            requireHttps(url),
            (data.long("interval") ?: DEFAULT_INTERVAL_SECONDS.toLong()).toInt(),
            (data.long("expires_in") ?: DEFAULT_EXPIRY_SECONDS).toInt(),
        )
    }

    override suspend fun poll(code: DeviceCode): DevicePoll {
        val answer = http.postJson(
            "${endpoints.torBox}/user/auth/device/token",
            buildJsonObject { put("device_code", JsonPrimitive(code.deviceCode)) },
        )
        val json = answer.json() ?: return pendingOr(answer, null)
        if (json.bool("success") == true) {
            val data = json.obj("data") ?: throw DebridServiceException()
            return DevicePoll.Linked(DeviceTokens(data.str("access_token") ?: throw DebridServiceException()))
        }
        return when (json.str("error")) {
            "DEVICE_CODE_NOT_USED", "authorization_pending" -> DevicePoll.Pending
            "ITEM_NOT_FOUND", "expired_token", "DEVICE_CODE_USED" -> DevicePoll.Expired
            "access_denied" -> DevicePoll.Denied
            else -> pendingOr(answer, json.str("error"))
        }
    }
}

/** Shared token parsing: `access_token`, optional `refresh_token` / `expires_in`. */
internal fun tokens(json: JsonObject): DeviceTokens = DeviceTokens(
    accessToken = json.str("access_token") ?: throw DebridServiceException(),
    refreshToken = json.str("refresh_token"),
    expiresInSeconds = json.long("expires_in"),
)

internal fun deviceCode(json: JsonObject, device: String, user: String, url: String): DeviceCode = DeviceCode(
    deviceCode = json.str(device) ?: throw DebridServiceException(),
    userCode = json.str(user) ?: throw DebridServiceException(),
    verificationUrl = requireHttps(json.str(url) ?: throw DebridServiceException()),
    intervalSeconds = (json.long("interval") ?: DEFAULT_INTERVAL_SECONDS.toLong()).toInt().coerceIn(1, MAX_INTERVAL_SECONDS),
    expiresInSeconds = (json.long("expires_in") ?: DEFAULT_EXPIRY_SECONDS).toInt().coerceAtLeast(1),
)

/**
 * A code that isn't finished: the standard OAuth error words map to their outcome, `429` backs off, `5xx` is
 * unreachable, and any other refusal means *still waiting* (Real-Debrid answers `403` until approval).
 */
internal fun pendingOr(answer: DebridAnswer, error: String?): DevicePoll = when {
    error == "expired_token" || error?.contains("expired", ignoreCase = true) == true -> DevicePoll.Expired
    error == "access_denied" || error?.contains("denied", ignoreCase = true) == true -> DevicePoll.Denied
    answer.code == HTTP_TOO_MANY_REQUESTS -> throw DebridRateLimitedException()
    answer.code >= HTTP_SERVER_ERROR -> throw java.io.IOException("HTTP ${answer.code}")
    else -> DevicePoll.Pending
}

/** The link page the portal shows must be an `https://` address on the service (never a script or other scheme). */
internal fun requireHttps(url: String): String {
    if (!url.startsWith("https://") || url.any { it.isWhitespace() || it.isISOControl() }) throw DebridServiceException()
    return url
}

internal fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

internal const val DEVICE_GRANT = "http://oauth.net/grant_type/device/1.0"
internal const val DEFAULT_INTERVAL_SECONDS = 5
internal const val DEFAULT_EXPIRY_SECONDS = 600L
private const val MAX_INTERVAL_SECONDS = 60
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
