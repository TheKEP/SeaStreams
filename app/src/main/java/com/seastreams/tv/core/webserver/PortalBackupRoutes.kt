package com.seastreams.tv.core.webserver

import com.seastreams.tv.data.UserFacingException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.serializer

/** Largest backup the portal accepts: five profiles with addon manifests and long watch histories (~350 B a record). */
internal const val MAX_BACKUP_CHARS = 16 * 1024 * 1024

/**
 * Backup endpoints under `/api` (PRD §8 *Backup & restore*), behind the same trusted-host guard as every portal route:
 * - `GET /api/backup/export?scope=profile|app&profileId=…&includeCredentials=true|false` – the backup as a JSON file
 *   download (`no-store`: the file may hold credentials)
 * - `POST /api/backup/restore?profileId=…` – the body is such a file (`application/json`, at most [MAX_BACKUP_CHARS]);
 *   its own `scope` decides whether it restores into `profileId` or restores every profile ([BackupRestoreResponse])
 */
internal fun Route.backupRoutes(controller: PortalBackupController) {
    get("/backup/export") {
        if (!call.hasTrustedHost()) return@get call.respondForbiddenHost()
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val query = call.request.queryParameters
        val file = try {
            controller.export(query["scope"], query["profileId"]?.toLongOrNull(), query["includeCredentials"] == "true")
        } catch (e: UserFacingException) {
            return@get call.respondJson(HttpStatusCode.BadRequest, ApiMessage(false, e.message.orEmpty()), serializer())
        }
        // File names hold only a-z, 0-9, '-' and '.', so the quoted name needs no escaping.
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${file.fileName}\"")
        call.respondText(file.json, ContentType.Application.Json)
    }
    post("/backup/restore") {
        call.guardedJson(serializer<BackupRestoreResponse>(), MAX_BACKUP_CHARS) {
            val profileId = call.request.queryParameters["profileId"]?.toLongOrNull()
            val body = call.receiveBoundedText(MAX_BACKUP_CHARS, "That file is too large to be a seastreams backup")
            controller.restore(profileId, body)
        }
    }
}
