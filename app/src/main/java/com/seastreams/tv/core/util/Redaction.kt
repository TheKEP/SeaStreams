package com.seastreams.tv.core.util

/**
 * How a secret appears in a `toString()`: only whether one is set (`null`, `""` or `***`), never any of its characters,
 * so a request, draft or backup model that reaches a log or an exception message can't leak it.
 */
fun redacted(secret: String?): String = when {
    secret == null -> "null"
    secret.isEmpty() -> "\"\""
    else -> "***"
}
