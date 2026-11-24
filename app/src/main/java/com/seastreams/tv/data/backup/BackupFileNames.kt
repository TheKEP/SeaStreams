package com.seastreams.tv.data.backup

import java.util.Locale

/** Download names for backups; slugs hold only `a-z`, `0-9` and `-`, so a quoted `Content-Disposition` needs no escaping. */
object BackupFileNames {

    const val FULL_BACKUP = "seastreams-full-backup.json"

    fun profileBackup(profileName: String): String = "seastreams-profile-${profileSlug(profileName)}.json"

    /** [profileName] lower-cased with every other run of characters turned into one `-`; `profile` when nothing is left. */
    fun profileSlug(profileName: String): String =
        profileName.lowercase(Locale.ROOT).replace(NOT_SLUG, "-").trim('-').take(MAX_SLUG_CHARS).trimEnd('-').ifEmpty { "profile" }

    private val NOT_SLUG = Regex("[^a-z0-9]+")
    private const val MAX_SLUG_CHARS = 40
}
