package com.seastreams.tv.data

/**
 * A rejected user action whose [message] is safe and meaningful to show verbatim, on the TV or in
 * the Web Portal (e.g. "A profile limit of 5 has been reached").
 */
class UserFacingException(message: String) : IllegalArgumentException(message)
