package com.seastreams.tv.core.webserver

import com.seastreams.tv.core.util.redacted
import kotlinx.serialization.Serializable

/** A service the portal can add (PRD §14). [deviceSignIn] is set only when its code sign-in is available. */
@Serializable
data class DebridServiceDto(
    val kind: String,
    val name: String,
    val keyLabel: String,
    val keyPageUrl: String,
    /** `device code`, `PIN code` or `OAuth code`; `null` = key / token paste only. */
    val deviceSignIn: String? = null,
)

/** A saved account: never its key or token, only whether one is stored and usable. */
@Serializable
data class DebridProviderDto(
    val id: Long,
    val kind: String,
    val name: String,
    val enabled: Boolean,
    /** `API_KEY` or `DEVICE`. */
    val authMethod: String,
    /** Always true: an account row exists only with a credential. */
    val hasCredential: Boolean,
    /** `API_KEY`, `SIGNED_IN` or `SIGN_IN_NEEDED`. */
    val credentialState: String,
    val credentialLabel: String,
)

/** `GET /api/debrid`. */
@Serializable
data class DebridPortalState(
    /** `Enable Debrid`. */
    val enabled: Boolean,
    val services: List<DebridServiceDto>,
    val providers: List<DebridProviderDto>,
)

/** Add ([id] = `null`) or edit an account; a blank [apiKey] on an edit keeps the stored one. */
@Serializable
data class DebridProviderRequest(
    val id: Long? = null,
    val kind: String,
    val apiKey: String? = null,
    val name: String? = null,
) {
    override fun toString(): String = "DebridProviderRequest($id, $kind, apiKey=${redacted(apiKey)})"
}

/** `Test`: a typed [apiKey] (with [kind]) is tested as is; otherwise the saved account [id]. */
@Serializable
data class DebridTestRequest(val id: Long? = null, val kind: String? = null, val apiKey: String? = null) {
    override fun toString(): String = "DebridTestRequest($id, $kind, apiKey=${redacted(apiKey)})"
}

@Serializable
data class DebridAuthStartRequest(val kind: String)

@Serializable
data class DebridAuthCancelRequest(val session: String)

/** A code sign-in as the portal shows it; [session] is a random handle, never the service's device code. */
@Serializable
data class DebridAuthSessionDto(
    val session: String,
    val kind: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Long,
    /** `pending`, `linked`, `expired`, `denied`, `cancelled` or `failed`. */
    val status: String,
)

/** `POST /api/sources/toggles`: only the fields present change. */
@Serializable
data class SourceTogglesRequest(val usenet: Boolean? = null, val debrid: Boolean? = null)
