package com.seastreams.tv.usenet.archive

/** A posted file as the selector sees it: its position in the NZB, resolved name and stated size. */
data class ReleaseFile(val index: Int, val name: String, val statedBytes: Long)

/** What to play from a release, as NZB file indices in reading order. */
sealed interface MediaPlan {
    data class Plain(val file: Int) : MediaPlan

    /** Raw `.001` byte splits (checked by signature at open: RAR inside is read as RAR, 7z as 7z). */
    data class Split(val files: List<Int>) : MediaPlan

    /** Stored RAR volumes; the entry is picked from the headers at open. */
    data class Rar(val files: List<Int>) : MediaPlan

    /** A `.7z` file or `.7z.001` … byte-split set; the entry is picked (and refused unless stored) at open. */
    data class SevenZip(val files: List<Int>) : MediaPlan

    data class Refused(val reason: String) : MediaPlan
}

/**
 * Picks what a release plays (PRD §12): files are grouped into plain videos, RAR sets, `.001` split sets and 7z sets
 * (duplicate volumes — the same index posted twice — collapse to the larger copy); par2 / nfo / sfv and other
 * support files are ignored; a group named `sample` / `proof` under 5% of the largest is excluded; the largest
 * remaining group wins. A zip winner, a set without its first volume, or a gap in the volume numbers is refused
 * (it can never stream), so failover moves on. 7z sets are opened by [SevenZipArchive] (stored entries only).
 */
object MediaSelector {
    private val SAMPLE = Regex("(?i)(^|[ ._\\-\\[(/])(sample|proof)([ ._\\-\\])]|$)")
    private const val SAMPLE_FRACTION = 0.05

    private enum class Kind { PLAIN, RAR, SPLIT, SEVEN_ZIP, ZIP }

    private class Group(val kind: Kind, val name: String, val files: List<ReleaseFile>, val indices: List<Int>) {
        val size: Long = files.sumOf { it.statedBytes }
    }

    fun select(files: List<ReleaseFile>): MediaPlan {
        val groups = groups(files)
        if (groups.isEmpty()) return MediaPlan.Refused("The release has no video file")
        val largest = groups.maxOf { it.size }
        val kept = groups.filterNot { SAMPLE.containsMatchIn(it.name) && it.size < largest * SAMPLE_FRACTION }
        val winner = kept.maxByOrNull { it.size } ?: return MediaPlan.Refused("The release has only a sample")
        return when (winner.kind) {
            Kind.ZIP -> MediaPlan.Refused("Zip archives can't be streamed")
            Kind.PLAIN -> MediaPlan.Plain(winner.files.single().index)
            Kind.RAR, Kind.SPLIT, Kind.SEVEN_ZIP -> {
                val gap = winner.indices.first() > 0 || winner.indices.zipWithNext().any { (a, b) -> b != a + 1 }
                when {
                    gap -> MediaPlan.Refused("A volume of the archive is missing")
                    winner.kind == Kind.RAR -> MediaPlan.Rar(winner.files.map { it.index })
                    winner.kind == Kind.SEVEN_ZIP -> MediaPlan.SevenZip(winner.files.map { it.index })
                    else -> MediaPlan.Split(winner.files.map { it.index })
                }
            }
        }
    }

    private fun groups(files: List<ReleaseFile>): List<Group> {
        val out = ArrayList<Group>()
        val sets = LinkedHashMap<Pair<VolumeStyle, String>, MutableMap<Int, ReleaseFile>>()
        for (file in files) {
            val ext = MediaExtensions.of(file.name)
            if (ext in MediaExtensions.IGNORED) continue
            val volume = RarVolumeNames.parse(file.name)
            when {
                volume != null -> {
                    val set = sets.getOrPut(volume.style to volume.base) { HashMap() }
                    val held = set[volume.index]
                    if (held == null || file.statedBytes > held.statedBytes) set[volume.index] = file
                }
                ext == "zip" -> out += Group(Kind.ZIP, file.name, listOf(file), listOf(0))
                MediaExtensions.isVideo(file.name) -> out += Group(Kind.PLAIN, file.name, listOf(file), listOf(0))
            }
        }
        for ((key, members) in sets) {
            val (style, base) = key
            val ordered = members.toSortedMap()
            val kind = when (style) {
                VolumeStyle.RAR_PART, VolumeStyle.RAR_OLD -> Kind.RAR
                VolumeStyle.SEVEN_ZIP -> Kind.SEVEN_ZIP
                VolumeStyle.SPLIT -> splitKind(base) ?: continue
            }
            // `.000`-based splits start one lower; renumber so the first file is index 0.
            val shift = if (style == VolumeStyle.SPLIT) ordered.firstKey().coerceAtMost(0) else 0
            out += Group(kind, base, ordered.values.toList(), ordered.keys.map { it - shift })
        }
        return out
    }

    /** A `.001` split of a video, of a RAR, or of a file with no extension (checked by signature); else not media. */
    private fun splitKind(base: String): Kind? {
        val ext = MediaExtensions.of(base)
        return when {
            ext == "rar" -> Kind.RAR
            ext == "zip" -> Kind.ZIP
            ext.isEmpty() || MediaExtensions.isVideo(base) || ext !in NON_MEDIA_SPLITS -> Kind.SPLIT
            else -> null
        }
    }

    private val NON_MEDIA_SPLITS = setOf("iso", "img", "bin", "exe", "tar", "gz", "par2")
}
