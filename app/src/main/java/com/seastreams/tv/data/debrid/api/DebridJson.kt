package com.seastreams.tv.data.debrid.api

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** Tolerant readers for the services' JSON: a missing, `null` or oddly typed field reads as `null`. */
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }

internal fun JsonObject.long(key: String): Long? {
    val value = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
    return value.longOrNull ?: value.doubleOrNull?.toLong() ?: value.content.trim().toLongOrNull()
}

internal fun JsonObject.bool(key: String): Boolean? {
    val value = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
    return value.booleanOrNull ?: value.longOrNull?.let { it != 0L }
}

/**
 * Epoch milliseconds from an ISO-8601 instant (`2027-03-03T12:00:00.000Z`), an offset or zone-less date-time (read as
 * UTC), or epoch seconds / milliseconds. `null` when unreadable.
 */
internal fun parseTimeMillis(text: String?): Long? {
    val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    value.toLongOrNull()?.let { return epochToMillis(it) }
    return try {
        Instant.parse(value).toEpochMilli()
    } catch (e: DateTimeParseException) {
        try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            try {
                LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (e: DateTimeParseException) {
                null
            }
        }
    }
}

/** Epoch seconds (services' usual unit) or milliseconds (anything past year ~5138 in seconds) to milliseconds. */
internal fun epochToMillis(value: Long): Long? = when {
    value <= 0 -> null
    value < EPOCH_MILLIS_THRESHOLD -> value * MILLIS_PER_SECOND
    else -> value
}

private const val EPOCH_MILLIS_THRESHOLD = 100_000_000_000L
