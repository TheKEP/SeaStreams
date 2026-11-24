package com.seastreams.tv.core.webserver

import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.backup.BackupExporter
import com.seastreams.tv.data.backup.BackupFile
import com.seastreams.tv.data.backup.BackupRestorer
import com.seastreams.tv.data.backup.BackupScope
import com.seastreams.tv.data.backup.InvalidAppBackupException
import com.seastreams.tv.data.backup.RestoreSummary
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/** `POST /api/backup/restore`: [ApiMessage]'s fields plus what was restored. */
@Serializable
data class BackupRestoreResponse(val ok: Boolean, val message: String, val summary: RestoreSummary? = null)

/** The portal's *Backup & restore* section (PRD §8): downloads and restores single-profile and full app backups. */
@Singleton
class PortalBackupController @Inject constructor(
    private val exporter: BackupExporter,
    private val restorer: BackupRestorer,
) {

    suspend fun export(scope: String?, profileId: Long?, includeCredentials: Boolean): BackupFile {
        val parsed = BackupScope.fromWire(scope) ?: throw UserFacingException("Choose what to back up")
        if (parsed == BackupScope.PROFILE && profileId == null) throw UserFacingException("Pick a profile")
        return exporter.export(parsed, profileId, includeCredentials)
    }

    /** Restores [json]; a single-profile backup lands on [profileId], a full one restores every profile it holds. */
    suspend fun restore(profileId: Long?, json: String): BackupRestoreResponse {
        val summary = try {
            restorer.restore(json, profileId)
        } catch (e: InvalidAppBackupException) {
            throw UserFacingException(e.message.orEmpty())
        }
        return BackupRestoreResponse(true, message(summary), summary)
    }

    internal companion object {
        fun message(s: RestoreSummary): String {
            val head = if (s.scope == BackupScope.PROFILE.wire) {
                "Backup restored into the selected profile"
            } else {
                "Restored ${count(s.profilesUpdated + s.profilesCreated, "profile")}" +
                    if (s.profilesCreated > 0) " (${s.profilesCreated} new)" else ""
            }
            val sources = listOfNotNull(
                count(s.providers, "Usenet provider").takeIf { s.providers > 0 },
                count(s.indexers, "indexer").takeIf { s.indexers > 0 },
                count(s.debridAccounts, "Debrid account").takeIf { s.debridAccounts > 0 },
            )
            val history = listOfNotNull(
                count(s.libraryItems, "library title").takeIf { s.libraryItems > 0 },
                count(s.watchProgress, "watch progress entry", "watch progress entries").takeIf { s.watchProgress > 0 },
            )
            val withSources = (if (sources.isEmpty()) head else "$head, with ${sources.joinToString(", ")}") +
                if (history.isEmpty()) "" else ". Added ${history.joinToString(" and ")}"
            val secrets = if (s.credentialsRestored > 0) {
                ". Credentials restored."
            } else {
                ". The file had no credentials: passwords, keys and tokens already on the TV were kept."
            }
            return withSources + secrets
        }

        private fun count(n: Int, noun: String, plural: String = noun + "s") = "$n ${if (n == 1) noun else plural}"
    }
}
