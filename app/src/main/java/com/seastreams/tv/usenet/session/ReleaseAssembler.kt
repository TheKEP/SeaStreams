package com.seastreams.tv.usenet.session

import com.seastreams.tv.usenet.archive.ArchiveKind
import com.seastreams.tv.usenet.archive.ArchiveRefusedException
import com.seastreams.tv.usenet.archive.ArchiveSignatures
import com.seastreams.tv.usenet.archive.JoinedData
import com.seastreams.tv.usenet.archive.MediaPlan
import com.seastreams.tv.usenet.archive.MediaSelector
import com.seastreams.tv.usenet.archive.ObfuscatedSetResolver
import com.seastreams.tv.usenet.archive.RandomAccessData
import com.seastreams.tv.usenet.archive.RarArchive
import com.seastreams.tv.usenet.archive.ReleaseFile
import com.seastreams.tv.usenet.archive.Span
import com.seastreams.tv.usenet.archive.readUpTo
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import kotlin.math.abs

/** The bytes a release plays: [data] of the media file [name], read from the NZB files [files]. */
class AssembledMedia(val name: String, val data: RandomAccessData, val files: List<Int>, val focus: ReadAheadFocus? = null)

/**
 * Turns a release's posted files into one playable byte range (PRD §12): resolve obfuscated names, pick the media
 * ([MediaSelector]), then read it as a plain file, as `.001` splits joined end to end (a RAR or 7z signature inside
 * a split is read as RAR / refused), or as the stored entry of a RAR set ([RarArchive]). Middle volumes whose stated
 * size matches the second volume's borrow its article grid, so no probe is spent on them. Only the volume being read
 * runs a full read-ahead window ([ReadAheadFocus]); probes and header reads of the others fetch just what they touch.
 * Blocking.
 */
class ReleaseAssembler(
    /** Runs probes / header reads in parallel (first articles of many volumes). */
    private val executor: Executor,
    private val lazyRar: Boolean,
    private val strictMembership: Boolean,
) {
    fun assemble(volumes: List<PostedVolume>): AssembledMedia {
        val focus = ReadAheadFocus(volumes)
        val names = ObfuscatedSetResolver.resolve(volumes) { tasks -> parallel(tasks) }
        volumes.forEachIndexed { i, volume -> volume.name = names[i] }
        val plan = MediaSelector.select(volumes.mapIndexed { i, v -> ReleaseFile(i, v.name, v.statedBytes) })
        // Only the media's last part holds the container index a player seeks to first.
        val last = when (plan) {
            is MediaPlan.Refused -> throw ArchiveRefusedException(plan.reason)
            is MediaPlan.Plain -> plan.file
            is MediaPlan.Split -> plan.files.last()
            is MediaPlan.Rar -> plan.files.last()
        }
        volumes[last].file.prefetchTail()
        return when (plan) {
            is MediaPlan.Refused -> throw ArchiveRefusedException(plan.reason)
            is MediaPlan.Plain -> {
                focus.focus(volumes[plan.file])
                AssembledMedia(volumes[plan.file].name, volumes[plan.file], listOf(plan.file), focus)
            }
            is MediaPlan.Split -> split(volumes, plan.files)
            is MediaPlan.Rar -> rar(volumes, plan.files)
        }.let { media -> following(media, focus) }
    }

    private fun following(media: AssembledMedia, focus: ReadAheadFocus): AssembledMedia {
        val data = media.data as? JoinedData ?: return media
        focus.follow(data)
        return AssembledMedia(media.name, data, media.files, focus)
    }

    private fun split(volumes: List<PostedVolume>, files: List<Int>): AssembledMedia {
        val parts = files.map(volumes::get)
        when (ArchiveSignatures.detect(parts[0].readUpTo(0, ArchiveSignatures.PROBE_BYTES))) {
            ArchiveKind.RAR4, ArchiveKind.RAR5 -> return rar(volumes, files)
            ArchiveKind.SEVEN_ZIP -> throw ArchiveRefusedException("7z archives can't be streamed")
            ArchiveKind.ZIP -> throw ArchiveRefusedException("Zip archives can't be streamed")
            ArchiveKind.NONE -> Unit
        }
        shareGrid(parts, reference = 0)
        parallel(parts.drop(1).map { part -> { part.size(); Unit } })
        val name = parts[0].name.substringBeforeLast('.')
        return AssembledMedia(name, JoinedData(parts.map { Span(it, 0, it.size()) }), files)
    }

    private fun rar(volumes: List<PostedVolume>, files: List<Int>): AssembledMedia {
        val parts = files.map(volumes::get)
        if (parts.size >= 3) shareGrid(parts, reference = 1)
        // Eager resolution reads every volume's first article: fetch them side by side, not one by one.
        val warm = if (lazyRar && parts.size >= 3) listOf(parts[0], parts[1], parts.last()) else parts
        parallel(warm.map { part -> { part.readUpTo(0, 1); Unit } })
        val set = RarArchive.open(parts, lazy = lazyRar, strict = strictMembership)
        return AssembledMedia(set.entryName.substringAfterLast('/'), set.data, files)
    }

    /** Middle parts (not the reference itself, not the last) with the reference's stated size borrow its grid. */
    private fun shareGrid(parts: List<PostedVolume>, reference: Int) {
        val model = parts[reference]
        for (i in reference + 1 until parts.size - 1) {
            if (abs(parts[i].statedBytes - model.statedBytes) <= model.statedBytes * GRID_TOLERANCE) parts[i].sibling = model
        }
    }

    private fun parallel(tasks: List<() -> Unit>) {
        val futures = tasks.map { task -> CompletableFuture.runAsync({ task() }, executor) }
        try {
            CompletableFuture.allOf(*futures.toTypedArray()).join()
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }
    }

    private companion object {
        /** NZB sizes are encoded sizes: equal volumes differ by a few yEnc escapes. */
        const val GRID_TOLERANCE = 0.005
    }
}
