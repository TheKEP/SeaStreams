package com.seastreams.tv.data.debrid.auth

import com.seastreams.tv.data.debrid.DebridCredentials
import com.seastreams.tv.data.debrid.DebridServiceKind
import com.seastreams.tv.data.debrid.api.DebridAuthException
import com.seastreams.tv.data.debrid.api.DebridEndpoints
import com.seastreams.tv.data.debrid.api.DebridHttp
import com.seastreams.tv.data.debrid.api.DebridServiceException
import com.seastreams.tv.data.debrid.api.requireJson
import com.seastreams.tv.data.debrid.api.str

/**
 * Debrid-Link's OAuth device flow, available only with a configured client ID (`BuildConfig.DEBRID_LINK_CLIENT_ID`):
 * `oauth/device/code` → poll `oauth/token` (device grant) → access + refresh token, refreshed with the
 * `refresh_token` grant.
 */
class DebridLinkDeviceFlow(
    private val http: DebridHttp,
    private val endpoints: DebridEndpoints,
    private val clientId: String,
) : DeviceFlow, TokenRefresh {
    override val kind = DebridServiceKind.DEBRID_LINK

    override suspend fun start(): DeviceCode {
        val json = http.postForm("${endpoints.debridLink}/oauth/device/code", mapOf("client_id" to clientId, "scope" to SCOPE))
            .throwOnHttpError().requireJson()
        return deviceCode(json, "device_code", "user_code", "verification_url")
    }

    override suspend fun poll(code: DeviceCode): DevicePoll {
        val answer = http.postForm(
            "${endpoints.debridLink}/oauth/token",
            mapOf("client_id" to clientId, "code" to code.deviceCode, "grant_type" to DEVICE_GRANT),
        )
        val json = answer.json()
        if (answer.ok && json?.str("access_token") != null) return DevicePoll.Linked(tokens(json))
        return pendingOr(answer, json?.str("error"))
    }

    override suspend fun refresh(credentials: DebridCredentials): DeviceTokens {
        val refresh = credentials.refreshToken ?: throw DebridAuthException("Sign-in needed")
        val answer = http.postForm(
            "${endpoints.debridLink}/oauth/token",
            mapOf("client_id" to clientId, "refresh_token" to refresh, "grant_type" to "refresh_token"),
        )
        if (answer.code == HTTP_BAD_REQUEST) throw DebridAuthException("Sign-in needed")
        val fresh = tokens(answer.throwOnHttpError().requireJson())
        // Debrid-Link may keep the same refresh token and omit it from the answer.
        return DeviceTokens(fresh.accessToken, fresh.refreshToken ?: refresh, expiresInSeconds = fresh.expiresInSeconds)
    }

    private companion object {
        /** Account check now; seedbox / downloader listings for the Milestone 12 library scanner. */
        const val SCOPE = "get.account get.post.seedbox get.post.downloader get.files"
    }
}

/**
 * put.io's out-of-band OAuth code, available only with a configured app ID (`BuildConfig.PUTIO_CLIENT_ID`):
 * `oauth2/oob/code` → the user enters the code at put.io/link → poll `oauth2/oob/code/{code}` until it carries an
 * `oauth_token` (which doesn't expire). put.io gives no lifetime, so the code is treated as valid for 10 minutes.
 */
class PutIoCodeFlow(
    private val http: DebridHttp,
    private val endpoints: DebridEndpoints,
    private val appId: String,
) : DeviceFlow {
    override val kind = DebridServiceKind.PUT_IO

    override suspend fun start(): DeviceCode {
        val json = http.get("${endpoints.putIo}/oauth2/oob/code?app_id=${enc(appId)}").throwOnHttpError().requireJson()
        val code = json.str("code") ?: throw DebridServiceException()
        return DeviceCode(code, code, LINK_PAGE, DEFAULT_INTERVAL_SECONDS, DEFAULT_EXPIRY_SECONDS.toInt())
    }

    override suspend fun poll(code: DeviceCode): DevicePoll {
        val answer = http.get("${endpoints.putIo}/oauth2/oob/code/${enc(code.deviceCode)}")
        if (answer.code == HTTP_NOT_FOUND) return DevicePoll.Expired
        val json = answer.throwOnHttpError().requireJson()
        return json.str("oauth_token")?.let { DevicePoll.Linked(DeviceTokens(it)) } ?: DevicePoll.Pending
    }

    private companion object {
        const val LINK_PAGE = "https://put.io/link"
    }
}

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_NOT_FOUND = 404
