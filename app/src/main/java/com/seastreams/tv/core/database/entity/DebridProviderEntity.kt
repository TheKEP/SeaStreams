package com.seastreams.tv.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A Debrid account (PRD §14). App-wide: no profile key, every profile shares it, and there is at most one row per
 * service ([providerType] is unique). Keys and tokens are only ever stored as `SecretCipher` output.
 */
@Entity(
    tableName = "debrid_providers",
    indices = [Index(value = ["providerType"], unique = true), Index("sortOrder")],
)
data class DebridProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** `DebridServiceKind` name: `REAL_DEBRID`, `ALL_DEBRID`, `TORBOX`, `PREMIUMIZE`, `DEBRID_LINK`, `OFFCLOUD`, `PUT_IO`. */
    val providerType: String,
    /** Shown on the TV row; the service name unless renamed in the portal. */
    val name: String,
    /** `API_KEY` (pasted) or `DEVICE` (device-code / PIN / OAuth sign-in). */
    val authMethod: String,
    /** The API key or access token. */
    val credentialCipher: String,
    /** Device sign-ins that expire (Real-Debrid, Debrid-Link): the refresh token. */
    val refreshTokenCipher: String?,
    /** Real-Debrid device sign-ins get a per-user client ID and secret, needed to refresh. */
    val clientIdCipher: String?,
    val clientSecretCipher: String?,
    /** When the access token expires (epoch ms); `null` = it doesn't. */
    val expiresAtMillis: Long?,
    /** The service rejected the credential (revoked key, failed refresh): shown as *Sign-in needed*. */
    val signInNeeded: Boolean,
    val enabled: Boolean,
    /** Position in the list (dense, starting at 0) = the order Debrid results are listed in. */
    val sortOrder: Int,
)
