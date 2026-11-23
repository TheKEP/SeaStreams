package com.seastreams.tv.usenet.archive

import java.io.IOException

/**
 * Bytes `[offset, offset + length)` of one volume. [verify] (lazy RAR resolution) runs before the first read: it
 * returns `true` when the volume's real header matches the assumed layout, or `false` after it replaced the layout
 * (the read then looks the position up again).
 */
class Span(val volume: RandomAccessData, val offset: Long, val length: Long, val verify: (() -> Boolean)? = null) {
    @Volatile
    internal var verified = verify == null
}

/** A virtual file made of [Span]s in order: a RAR entry spread over volumes, or raw `.001` splits. */
class JoinedData(spans: List<Span>) : RandomAccessData {
    private class Layout(val spans: List<Span>) {
        val starts = LongArray(spans.size).also { starts ->
            var at = 0L
            spans.forEachIndexed { i, span ->
                starts[i] = at
                at += span.length
            }
        }
        val total = spans.sumOf { it.length }
    }

    @Volatile
    private var layout = Layout(spans)

    val spans: List<Span> get() = layout.spans

    /** Told when reading moves to another span's volume (only the volume being played reads ahead). */
    @Volatile
    var onVolumeChange: ((RandomAccessData) -> Unit)? = null

    @Volatile
    private var current: RandomAccessData? = null

    fun replace(spans: List<Span>) {
        layout = Layout(spans)
    }

    override fun size(): Long = layout.total

    override fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        repeat(MAX_RELAYOUTS) {
            val current = layout
            if (position >= current.total) return -1
            if (length == 0) return 0
            val index = spanAt(current, position)
            val span = current.spans[index]
            if (!span.verified) {
                val matches = checkNotNull(span.verify)()
                if (!matches) return@repeat
                span.verified = true
            }
            if (this.current !== span.volume) {
                this.current = span.volume
                onVolumeChange?.invoke(span.volume)
            }
            val within = position - current.starts[index]
            val count = minOf(length.toLong(), span.length - within).toInt()
            val n = span.volume.read(span.offset + within, target, offset, count)
            if (n < 0) throw IOException("A volume ended before its stated size")
            return n
        }
        throw IOException("The archive's volumes keep changing layout")
    }

    private fun spanAt(current: Layout, position: Long): Int {
        var low = 0
        var high = current.spans.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (current.starts[mid] <= position) low = mid else high = mid - 1
        }
        return low
    }

    private companion object {
        const val MAX_RELAYOUTS = 3
    }
}

/** The RAR entry chosen for playback and the bytes it occupies across the volumes. */
class RarSet(val entryName: String, val data: JoinedData)

/**
 * Resolves a stored RAR4/RAR5 set into the one video entry's bytes (PRD §12, ARCHITECTURE §10 Archives).
 * - Headers encrypted, entry encrypted, or entry compressed → [ArchiveRefusedException].
 * - Eager: every volume header is parsed up front. *Strict archive membership* refuses a volume that doesn't hold
 *   the entry; otherwise such a stray volume is skipped. The spans must add up to the entry's size.
 * - Lazy (*Lazy RAR resolution*, 3+ volumes, size known): the first, second and last volumes are parsed; the
 *   middle ones are assumed to repeat the second's layout and each is checked just before its first read; a
 *   mismatch re-resolves everything eagerly.
 */
object RarArchive {
    private const val SIZE_TOLERANCE = 0.02

    fun open(volumes: List<VolumeData>, lazy: Boolean, strict: Boolean): RarSet {
        require(volumes.isNotEmpty())
        val first = RarVolumeParser.parse(volumes[0])
        if (first.headersEncrypted) throw ArchiveRefusedException("The archive is password-protected")
        val entry = pick(first.entries)
        val head = Span(volumes[0], entry.dataOffset, entry.packedSize)
        if (!entry.splitAfter || volumes.size == 1) return RarSet(entry.name, JoinedData(listOf(head)))
        val data = JoinedData(listOf(head))
        val lazyLayout = if (lazy) lazySpans(volumes, entry, head, data, strict) else null
        data.replace(lazyLayout ?: eagerSpans(volumes, entry, head, strict))
        return RarSet(entry.name, data)
    }

    private fun pick(entries: List<RarEntry>): RarEntry {
        val files = entries.filter { !it.directory }
        val entry = files.filter { MediaExtensions.isVideo(it.name) }.maxByOrNull { maxOf(it.unpackedSize, it.packedSize) }
            ?: throw ArchiveRefusedException(if (files.isEmpty()) "The archive lists no files" else "The archive holds no video")
        checkStreamable(entry)
        return entry
    }

    private fun checkStreamable(entry: RarEntry) {
        if (entry.encrypted) throw ArchiveRefusedException("The archive is password-protected")
        if (!entry.stored) throw ArchiveRefusedException("The archive is compressed (only stored archives can stream)")
    }

    private fun eagerSpans(volumes: List<VolumeData>, entry: RarEntry, head: Span, strict: Boolean): List<Span> {
        val spans = arrayListOf(head)
        var open = entry.splitAfter
        for (i in 1 until volumes.size) {
            if (!open) break
            val part = memberEntry(volumes[i], entry.name, i, strict) ?: continue
            spans += Span(volumes[i], part.dataOffset, part.packedSize)
            open = part.splitAfter
        }
        val total = spans.sumOf { it.length }
        when {
            entry.unpackedSize >= 0 && total < entry.unpackedSize -> throw ArchiveRefusedException("A volume of the archive is missing")
            entry.unpackedSize >= 0 && total > entry.unpackedSize && strict -> throw ArchiveRefusedException("The archive's volumes don't add up")
            open && strict -> throw ArchiveRefusedException("The archive's last volume is missing")
        }
        return spans
    }

    private fun lazySpans(volumes: List<VolumeData>, entry: RarEntry, head: Span, data: JoinedData, strict: Boolean): List<Span>? {
        val n = volumes.size
        if (n < 3 || entry.unpackedSize < 0) return null
        val second = memberEntry(volumes[1], entry.name, 1, strict = false) ?: return null
        val last = memberEntry(volumes[n - 1], entry.name, n - 1, strict = false) ?: return null
        val expectedLast = entry.unpackedSize - head.length - (n - 2) * second.packedSize
        if (last.packedSize != expectedLast || last.splitAfter || !second.splitAfter) return null
        val reference = volumes[1].statedBytes.toDouble()
        val uniform = (2 until n - 1).all { kotlin.math.abs(volumes[it].statedBytes - reference) <= reference * SIZE_TOLERANCE }
        if (!uniform) return null
        val spans = ArrayList<Span>(n)
        spans += head
        spans += Span(volumes[1], second.dataOffset, second.packedSize)
        for (i in 2 until n - 1) {
            val volume = volumes[i]
            spans += Span(volume, second.dataOffset, second.packedSize) {
                val real = memberEntry(volume, entry.name, i, strict = false)
                val fits = real != null && real.dataOffset == second.dataOffset && real.packedSize == second.packedSize && real.splitAfter
                if (!fits) data.replace(eagerSpans(volumes, entry, head, strict))
                fits
            }
        }
        spans += Span(volumes[n - 1], last.dataOffset, last.packedSize)
        return spans
    }

    /** [volume]'s header for [name]; `null` (non-strict) or a refusal (strict) when it doesn't hold it. */
    private fun memberEntry(volume: VolumeData, name: String, index: Int, strict: Boolean): RarEntry? {
        val info = try {
            RarVolumeParser.parse(volume)
        } catch (e: RarFormatException) {
            if (strict) throw ArchiveRefusedException("Volume ${index + 1} of the archive is damaged") else return null
        }
        if (info.headersEncrypted) throw ArchiveRefusedException("The archive is password-protected")
        val entry = info.entries.firstOrNull { it.name == name && it.splitBefore }
        if (entry == null) {
            if (strict) throw ArchiveRefusedException("Volume ${index + 1} doesn't belong to the archive")
            return null
        }
        checkStreamable(entry)
        return entry
    }
}
