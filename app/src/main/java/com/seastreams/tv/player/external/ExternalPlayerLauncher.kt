package com.seastreams.tv.player.external

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import com.seastreams.tv.core.datastore.PlayerChoice
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.model.SubtitleTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** An installed app that can open video links. */
data class InstalledPlayer(val packageName: String, val label: String)

/**
 * Finds every installed app registered for `ACTION_VIEW` with a video MIME type (Just Player, MX Player, Vimu, VLC,
 * Kodi, …). The manifest's `<queries>` entry makes them visible on Android 11+.
 */
@Singleton
class InstalledVideoPlayers @Inject constructor(@param:ApplicationContext private val context: Context) {

    suspend fun list(): List<InstalledPlayer> = withContext(Dispatchers.IO) {
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(PROBE_URL), VIDEO_MIME)
        val pm = context.packageManager
        @Suppress("DEPRECATION") // the flags overload needs API 33; minSdk is 26
        val matches = pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        matches.map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { (pkg, _) -> pkg != context.packageName }
            .distinctBy { it.first }
            .map { (pkg, label) -> InstalledPlayer(pkg, label.ifBlank { pkg }) }
            .sortedBy { it.label.lowercase() }
    }

    private companion object {
        const val PROBE_URL = "https://example.invalid/video.mp4"
    }
}

/** Everything needed to hand one stream to an external player. */
data class ExternalLaunchSpec(
    val choice: PlayerChoice,
    val stream: StreamSource,
    val title: String,
    val startPositionMs: Long,
    /** Preferred-language tracks first. */
    val subtitles: List<SubtitleTrack>,
    /** Subtitle to switch on at start (Subtitles: Always On), if any. */
    val enabledSubtitle: SubtitleTrack?,
)

/**
 * Builds the `ACTION_VIEW` intent for [ExternalLaunchSpec], launched with
 * `ActivityResultContracts.StartActivityForResult` so the player's `position`/`duration`/`end_by` come
 * back. Resume and subtitle extras follow the MX Player / Just Player API, which Vimu also reads; VLC
 * takes its resume position as a long.
 */
object ExternalPlayerIntents {

    fun build(spec: ExternalLaunchSpec): Intent? {
        val packageName = when (val choice = spec.choice) {
            PlayerChoice.BuiltIn -> return null
            PlayerChoice.SystemChooser -> null
            is PlayerChoice.App -> choice.packageName
        }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(spec.stream.url), VIDEO_MIME)
            putExtra(EXTRA_TITLE, spec.title)
            putExtra(EXTRA_RETURN_RESULT, true)
            val position = spec.startPositionMs.coerceAtLeast(0)
            if (position > 0) {
                if (packageName == VLC_PACKAGE) putExtra(EXTRA_POSITION, position) else putExtra(EXTRA_POSITION, position.toIntMs())
                putExtra(EXTRA_START_FROM, position.toIntMs())
            }
            if (packageName == VLC_PACKAGE) putExtra(EXTRA_VLC_FROM_START, position == 0L)
            if (spec.stream.headers.isNotEmpty()) {
                putExtra(EXTRA_HEADERS, spec.stream.headers.flatMap { (k, v) -> listOf(k, v) }.toTypedArray())
            }
            if (spec.subtitles.isNotEmpty()) {
                putExtra(EXTRA_SUBS, spec.subtitles.map { Uri.parse(it.url) }.toTypedArray())
                putExtra(EXTRA_SUBS_NAME, spec.subtitles.map { "${it.language.uppercase()} · ${it.addonName}" }.toTypedArray())
                spec.enabledSubtitle?.let { putExtra(EXTRA_SUBS_ENABLE, arrayOf(Uri.parse(it.url))) }
            }
            if (packageName != null) setPackage(packageName)
        }
        return if (packageName == null) Intent.createChooser(view, CHOOSER_TITLE) else view
    }

    /** Result extras as a plain map, for [com.seastreams.tv.domain.playback.ExternalPlayerExtras.from]. */
    fun resultExtras(data: Intent?): Map<String, Any?> {
        val extras: Bundle = data?.extras ?: return emptyMap()
        @Suppress("DEPRECATION") // typed getters can't read "whatever number type the player used"
        return extras.keySet().associateWith { extras.get(it) }
    }

    private fun Long.toIntMs(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private const val CHOOSER_TITLE = "Play with"
    private const val VLC_PACKAGE = "org.videolan.vlc"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_RETURN_RESULT = "return_result"
    private const val EXTRA_POSITION = "position"
    private const val EXTRA_START_FROM = "startfrom"
    private const val EXTRA_VLC_FROM_START = "from_start"
    private const val EXTRA_HEADERS = "headers"
    private const val EXTRA_SUBS = "subs"
    private const val EXTRA_SUBS_NAME = "subs.name"
    private const val EXTRA_SUBS_ENABLE = "subs.enable"
}

private const val VIDEO_MIME = "video/*"
