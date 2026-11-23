package com.seastreams.tv.data.debrid

import com.seastreams.tv.data.UserFacingException

/** How a Debrid account was connected. */
enum class DebridAuthMethod { API_KEY, DEVICE }

/** The kind of code a service's device sign-in shows (PRD §14 *Device code sign-in*). */
enum class DeviceSignIn(val label: String) {
    DEVICE_CODE("device code"),
    PIN("PIN code"),
    OAUTH_CODE("OAuth code"),
}

/**
 * The seven supported Debrid services (PRD §14): one account each. [deviceSignIn] is the code-based sign-in the
 * service offers; for Debrid-Link and put.io it only switches on once a client ID is configured
 * ([DebridClientIds]). Premiumize and Offcloud are key-only.
 */
enum class DebridServiceKind(
    val displayName: String,
    /** What the pasted credential is called on the service's site. */
    val keyLabel: String,
    /** Where the user finds that key or token. */
    val keyPageUrl: String,
    val deviceSignIn: DeviceSignIn?,
) {
    REAL_DEBRID("Real-Debrid", "API token", "https://real-debrid.com/apitoken", DeviceSignIn.DEVICE_CODE),
    ALL_DEBRID("AllDebrid", "API key", "https://alldebrid.com/apikeys/", DeviceSignIn.PIN),
    TORBOX("TorBox", "API key", "https://torbox.app/settings", DeviceSignIn.DEVICE_CODE),
    PREMIUMIZE("Premiumize", "API key", "https://www.premiumize.me/account", null),
    DEBRID_LINK("Debrid-Link", "API key", "https://debrid-link.com/webapp/apikey", DeviceSignIn.DEVICE_CODE),
    OFFCLOUD("Offcloud", "API key", "https://offcloud.com/#/account", null),
    PUT_IO("put.io", "OAuth token", "https://app.put.io/oauth", DeviceSignIn.OAUTH_CODE),
    ;

    /** Whether the portal offers a device / PIN / OAuth-code sign-in for this service with [clientIds]. */
    fun deviceSignInAvailable(clientIds: DebridClientIds): Boolean = when (this) {
        DEBRID_LINK -> clientIds.debridLink.isNotBlank()
        PUT_IO -> clientIds.putIo.isNotBlank()
        else -> deviceSignIn != null
    }

    companion object {
        fun fromName(name: String): DebridServiceKind? = entries.firstOrNull { it.name == name }
    }
}

/** OAuth client IDs from `BuildConfig` (blank = that service's code sign-in is unavailable). */
data class DebridClientIds(val debridLink: String = "", val putIo: String = "")

/** What the TV row and the portal show about a stored credential (never the credential itself). */
enum class DebridCredentialState(val label: String) {
    API_KEY("API key"),
    SIGNED_IN("Signed in"),
    SIGN_IN_NEEDED("Sign-in needed"),
}

/** A Debrid account as the Sources tab and the portal show it: no key or token. */
data class DebridProvider(
    val id: Long,
    val kind: DebridServiceKind,
    val name: String,
    val authMethod: DebridAuthMethod,
    val credentialState: DebridCredentialState,
    val enabled: Boolean,
)

/** Decrypted credentials, for the service clients only. [toString] never prints a secret. */
class DebridCredentials(
    val providerId: Long,
    val kind: DebridServiceKind,
    val accessToken: String,
    val refreshToken: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val expiresAtMillis: Long? = null,
) {
    override fun toString(): String = "DebridCredentials($providerId, $kind)"
}

/** Key / token and name checks shared by the TV and the portal. */
object DebridSourceValidation {
    const val MIN_KEY_CHARS = 8
    const val MAX_KEY_CHARS = 4096
    const val MAX_NAME_CHARS = 40

    /** A trimmed key or token: 8..4096 characters, no whitespace or control characters (so no CR/LF header injection). */
    fun key(kind: DebridServiceKind, raw: String): String {
        val key = raw.trim()
        if (key.length !in MIN_KEY_CHARS..MAX_KEY_CHARS || key.any { it.isWhitespace() || it.isISOControl() }) {
            throw UserFacingException("Enter a valid ${kind.displayName} ${kind.keyLabel}")
        }
        return key
    }

    /** The row name: the service name when blank. */
    fun name(kind: DebridServiceKind, raw: String?): String {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty()) return kind.displayName
        if (name.length > MAX_NAME_CHARS || name.any { it.isISOControl() }) throw UserFacingException("Enter a shorter name")
        return name
    }
}
