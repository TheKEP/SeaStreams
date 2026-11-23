package com.seastreams.tv.usenet.archive

import com.seastreams.tv.usenet.verify.ContentSniffer
import com.seastreams.tv.usenet.verify.MediaContainer
import java.io.IOException

/** One posted file whose name may be obfuscated: what can be learned about it by fetching its first article. */
interface NameProbe {
    /** The file name from the NZB subject, if any. */
    val subjectName: String?

    /** `=ybegin name=` of the first article (fetches it). */
    fun yencName(): String

    /** The first bytes of the file (from the same article). */
    fun head(): ByteArray

    /** RAR headers of this file (only asked for files whose head is a RAR signature). */
    fun rarInfo(): RarVolumeInfo
}

/**
 * Names for obfuscated releases (PRD §12): a subject that names a known file kind is used as-is; otherwise the
 * first article's yEnc `name=` (posters often obfuscate only the subject). Files still unrecognisable are
 * identified by signature: RAR volumes are ordered by their RAR5 volume number (RAR4: the first-volume flag, then
 * NZB order) and named `obfuscated.partNNN.rar`; a known video container gets that extension. Anything else keeps
 * its unrecognised name and is ignored by [MediaSelector].
 */
object ObfuscatedSetResolver {
    private const val SYNTHETIC = "obfuscated"

    /** Whether a name says what the file is (a known extension or volume pattern). */
    fun recognisable(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        if (RarVolumeNames.parse(name) != null) return true
        val ext = MediaExtensions.of(name)
        return ext in MediaExtensions.VIDEO || ext in MediaExtensions.IGNORED || ext == "zip" || ext == "srt" || ext == "sub"
    }

    /** One resolved name per probe, in the same order; [probe] runs the network reads for unclear files. */
    fun resolve(files: List<NameProbe>, probe: (List<() -> Unit>) -> Unit = { tasks -> tasks.forEach { it() } }): List<String> {
        val names = arrayOfNulls<String>(files.size)
        val unclear = ArrayList<Int>()
        files.forEachIndexed { i, file -> if (recognisable(file.subjectName)) names[i] = file.subjectName else unclear += i }
        if (unclear.isEmpty()) return names.map { it.orEmpty() }
        val yenc = arrayOfNulls<String>(files.size)
        val heads = arrayOfNulls<ByteArray>(files.size)
        probe(unclear.map { i -> { readProbe(files[i], i, yenc, heads) } })
        val rarFiles = ArrayList<Pair<Int, Int?>>() // index → volume number
        for (i in unclear) {
            val fromYenc = yenc[i]
            if (recognisable(fromYenc)) {
                names[i] = fromYenc
                continue
            }
            val head = heads[i] ?: ByteArray(0)
            when (ArchiveSignatures.detect(head)) {
                ArchiveKind.RAR4, ArchiveKind.RAR5 -> rarFiles += i to volumeNumber(files[i])
                ArchiveKind.SEVEN_ZIP -> names[i] = "$SYNTHETIC-$i.7z"
                ArchiveKind.ZIP -> names[i] = "$SYNTHETIC-$i.zip"
                ArchiveKind.NONE -> {
                    val container = ContentSniffer.sniff(head)
                    names[i] = if (container == MediaContainer.UNKNOWN) fromYenc ?: files[i].subjectName ?: "$SYNTHETIC-$i" else "$SYNTHETIC-$i.${container.extension}"
                }
            }
        }
        // First volume first (number 0 / RAR4 first-volume flag), then by number, then NZB order.
        rarFiles.sortedWith(compareBy({ it.second ?: Int.MAX_VALUE }, { it.first })).forEachIndexed { order, (i, _) ->
            names[i] = "$SYNTHETIC.part%03d.rar".format(order + 1)
        }
        return names.map { it.orEmpty() }
    }

    private fun readProbe(file: NameProbe, i: Int, yenc: Array<String?>, heads: Array<ByteArray?>) {
        try {
            yenc[i] = file.yencName().trim().takeIf { it.isNotEmpty() }
            heads[i] = file.head()
        } catch (e: IOException) {
            // An unreadable file stays unnamed and is ignored by the selector.
        }
    }

    private fun volumeNumber(file: NameProbe): Int? = try {
        file.rarInfo().volumeNumber
    } catch (e: IOException) {
        null
    }
}
