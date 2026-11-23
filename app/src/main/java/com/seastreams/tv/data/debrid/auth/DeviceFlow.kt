package com.seastreams.tv.data.debrid.auth

import com.seastreams.tv.data.debrid.DebridCredentials
import com.seastreams.tv.data.debrid.DebridServiceKind

/** A code the user enters on the service's link page. [deviceCode] is the secret polling handle: never shown or returned. */
class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
) {
    override fun toString(): String = "DeviceCode($userCode, $verificationUrl)"
}

/** Tokens a finished sign-in or refresh hands back. [toString] never prints a secret. */
class DeviceTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    /** Lifetime of [accessToken]; `null` = it doesn't expire. */
    val expiresInSeconds: Long? = null,
) {
    override fun toString(): String = "DeviceTokens(expiresIn=$expiresInSeconds)"
}

/** One poll of a pending code. */
sealed interface DevicePoll {
    data object Pending : DevicePoll

    class Linked(val tokens: DeviceTokens) : DevicePoll

    data object Expired : DevicePoll

    data object Denied : DevicePoll
}

/**
 * A service's code sign-in. [start] asks for a code; [poll] is called every `intervalSeconds` until it isn't
 * [DevicePoll.Pending]. Network trouble is an `IOException`; a refused request a `DebridException`.
 */
interface DeviceFlow {
    val kind: DebridServiceKind

    suspend fun start(): DeviceCode

    suspend fun poll(code: DeviceCode): DevicePoll
}

/** Services whose device sign-ins expire and can be renewed with a refresh token. */
interface TokenRefresh {
    /** New tokens for [credentials]; a refused refresh is a `DebridAuthException`. */
    suspend fun refresh(credentials: DebridCredentials): DeviceTokens
}

/** Looks up the [TokenRefresh] of a service, if it has one. */
fun interface TokenRefreshers {
    fun of(kind: DebridServiceKind): TokenRefresh?
}
