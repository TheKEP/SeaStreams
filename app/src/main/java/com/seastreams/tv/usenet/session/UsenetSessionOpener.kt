package com.seastreams.tv.usenet.session

import com.seastreams.tv.core.util.IoDispatcher
import com.seastreams.tv.core.util.daemonExecutor
import com.seastreams.tv.data.usenet.newznab.NewznabException
import com.seastreams.tv.data.usenet.newznab.NotAnNzbException
import com.seastreams.tv.data.usenet.newznab.NzbGrabber
import com.seastreams.tv.data.usenet.newznab.ResponseTooLargeException
import com.seastreams.tv.domain.usenet.UsenetPerformance
import com.seastreams.tv.domain.usenet.VerifyMode
import com.seastreams.tv.usenet.archive.UsenetUnplayableException
import com.seastreams.tv.usenet.archive.readUpTo
import com.seastreams.tv.usenet.nntp.DefaultSocketOpener
import com.seastreams.tv.usenet.nntp.FetchPriority
import com.seastreams.tv.usenet.nntp.NntpEngine
import com.seastreams.tv.usenet.nntp.engineSettings
import com.seastreams.tv.usenet.nntp.monotonicMillis
import com.seastreams.tv.usenet.nzb.Nzb
import com.seastreams.tv.usenet.nzb.NzbParseException
import com.seastreams.tv.usenet.nzb.NzbParser
import com.seastreams.tv.usenet.stream.SegmentArena
import com.seastreams.tv.usenet.stream.UsenetFile
import com.seastreams.tv.usenet.verify.ContentSniffer
import com.seastreams.tv.usenet.verify.HoleBudget
import com.seastreams.tv.usenet.verify.HoleFillingSource
import com.seastreams.tv.usenet.verify.HoleSlot
import com.seastreams.tv.usenet.verify.MediaContainer
import com.seastreams.tv.usenet.verify.ReleaseHealth
import com.seastreams.tv.usenet.verify.ReleaseHealthCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import java.io.Closeable
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens one Usenet stream (ARCHITECTURE §10 Playback): token → grab → NZB (cached, *Max NZB size*) → posted files on
 * the configured engine (an addon's own `servers` get a private engine without the disk cache) → media
 * ([ReleaseAssembler]) → *Verify file contents* sniff → census ([SessionVerification]). Lasting failures are
 * [UsenetUnplayableException]s and are remembered in [ReleaseHealthCache]; network trouble stays a plain
 * [IOException] so the player may retry. Blocking ([open]) for loader and proxy threads.
 */
@Singleton
class UsenetSessionOpener @Inject constructor(
    private val streams: UsenetStreamRegistry,
    private val setup: UsenetEngineSetup,
    private val grabber: NzbGrabber,
    private val health: ReleaseHealthCache,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SessionOpener {
    private val probes by lazy { daemonExecutor(PROBE_THREADS, "usenet-probe") }

    override fun open(token: String): UsenetSession = runBlocking(io) { openSuspend(token) }

    suspend fun openSuspend(token: String): UsenetSession {
        val grab = streams.get(token) ?: throw UsenetUnplayableException("This Usenet result has expired. Look up the streams again.")
        val configured = setup.configure()
        val performance = configured.performance
        val privateEngine = (grab as? UsenetGrab.FromAddon)?.servers?.takeIf { it.isNotEmpty() }?.let { servers ->
            NntpEngine(DefaultSocketOpener, monotonicMillis, null).apply { configure(servers, performance.engineSettings()) }
        }
        val engine = privateEngine ?: configured.engine
        if (privateEngine == null && configured.endpoints.isEmpty()) {
            throw UsenetUnplayableException("Add a Usenet provider in Settings > Sources")
        }
        val resources = ArrayList<Closeable>()
        privateEngine?.let(resources::add)
        try {
            val nzb = remembering(token) { NzbParser.parse(grabNzb(grab, performance)) }
            return runInterruptible(io) { build(token, nzb, engine, performance, resources) }
        } catch (e: Throwable) {
            resources.forEach { runCatching { it.close() } }
            throw e
        }
    }

    private suspend fun grabNzb(grab: UsenetGrab, performance: UsenetPerformance): String = try {
        grabber.grab(grab.nzb, performance.maxNzbSizeMb * MB, performance.maxConcurrentImports)
    } catch (e: NewznabException) {
        if (e.accountProblem) throw UsenetUnplayableException(e.message.orEmpty()) else throw e
    } catch (e: NotAnNzbException) {
        throw UsenetUnplayableException(e.message.orEmpty())
    } catch (e: ResponseTooLargeException) {
        throw UsenetUnplayableException("The NZB is too large: ${e.message}")
    }

    private fun build(token: String, nzb: Nzb, engine: NntpEngine, performance: UsenetPerformance, resources: MutableList<Closeable>): UsenetSession {
        val settings = engine.settings
        val arena = SegmentArena(SegmentArena.sizeFor(performance.readAheadSegments))
        resources += Closeable { arena.clear() }
        val totalArticles = nzb.files.sumOf { it.segments.size }
        var matroska = false
        lateinit var files: List<UsenetFile>
        val owners = HashMap<String, Pair<Int, Int>>()
        nzb.files.forEachIndexed { f, file -> file.segments.forEachIndexed { i, s -> owners.putIfAbsent(s.messageId, f to i) } }
        val source = HoleFillingSource(engine.cachedSource, performance.damagePolicy, HoleBudget(totalArticles), slot = { id ->
            owners[id]?.let { (f, i) -> slotOf(files[f], f, i) }
        }, matroska = { matroska })
        files = nzb.files.map { nzbFile ->
            UsenetFile(nzbFile, source, arena, performance.readAheadSegments, settings.segmentTimeoutMs).apply { tailPrefetch = false }
        }
        resources += files
        // Last: once the arena and windows let go, the engine's idle decode buffers go back to the GC too.
        resources += Closeable { engine.bufferPool.trim() }
        val volumes = files.mapIndexed { i, file -> PostedVolume(file, file.file.filename ?: "file-$i") }
        val media = remembering(token) {
            ReleaseAssembler(probes, performance.lazyRarResolution, performance.strictArchiveMembership).assemble(volumes)
        }
        val head = media.data.readUpTo(0, ContentSniffer.HEAD_BYTES)
        val container = ContentSniffer.sniff(head).takeUnless { it == MediaContainer.UNKNOWN } ?: ContentSniffer.byName(media.name)
        if (performance.verifyFileContents && ContentSniffer.sniff(head) == MediaContainer.UNKNOWN) {
            health.record(token, ReleaseHealth.NotStreamable("not a video"))
            throw UsenetUnplayableException("The release isn't a video file")
        }
        matroska = performance.matroskaHoleRepair && (container == MediaContainer.MATROSKA || container == MediaContainer.WEBM)
        val session = UsenetSession(
            token, media.name, media.data, resources, performance.streamIdleTimeoutSeconds * 1_000L, container, trim = { arena.clear(); engine.bufferPool.trim() },
        )
        if (performance.verifyMode == VerifyMode.CENSUS) {
            SessionVerification(health, performance).start(session, media.files.map { nzb.files[it] }) { id -> engine.stat(id, FetchPriority.LOW) }
                ?.let(resources::add)
        } else {
            health.record(token, ReleaseHealth.Healthy)
        }
        return session
    }

    /** Where article [index] of [file] sits once its layout is known; the first article can never be filled. */
    private fun slotOf(file: UsenetFile, fileIndex: Int, index: Int): HoleSlot? {
        if (index == 0 || !file.opened || file.partSize <= 0) return null
        val begin = index * file.partSize
        if (begin >= file.size) return null
        val length = minOf(file.partSize, file.size - begin).toInt()
        return HoleSlot(fileIndex, index, begin, length, file.size, file.name, file.file.segments.size)
    }

    /** Lasting refusals are remembered, so the next search drops the release. */
    private inline fun <T> remembering(token: String, block: () -> T): T = try {
        block()
    } catch (e: UsenetUnplayableException) {
        health.record(token, ReleaseHealth.NotStreamable(e.message.orEmpty()))
        throw e
    } catch (e: NzbParseException) {
        health.record(token, ReleaseHealth.NotStreamable(e.message.orEmpty()))
        throw UsenetUnplayableException(e.message ?: "The NZB couldn't be read")
    }

    private companion object {
        const val MB = 1024L * 1024
        const val PROBE_THREADS = 8
    }
}
