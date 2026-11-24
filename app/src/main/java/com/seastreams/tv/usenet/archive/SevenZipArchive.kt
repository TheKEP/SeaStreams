package com.seastreams.tv.usenet.archive

import com.seastreams.tv.usenet.verify.ContentSniffer
import com.seastreams.tv.usenet.verify.MediaContainer
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.IOException

/**
 * The 7z entry chosen for playback: [data] is what the player reads, [follow] the archive bytes underneath it on
 * the volumes (the same object for a Copy entry; the encrypted pack stream under an AES entry), so the read-ahead
 * can follow the volume being played.
 */
class SevenZipSet(val entryName: String, val data: RandomAccessData, val follow: JoinedData)

/**
 * Opens a single `.7z` or joined `.7z.001` … set (PRD §12, ARCHITECTURE §10 Archives) with commons-compress over
 * [SevenZipChannel] and picks the largest video entry.
 * - Copy entry → an exact [JoinedData.slice] of the volumes (pure random access, no decoding).
 * - AES + Copy entry (any password-protected 7z, with or without encrypted headers) → [SevenZipAesData] keyed from
 *   the NZB password ([com.seastreams.tv.usenet.nzb.Nzb.password]).
 * - Compressed / filtered entries (LZMA, LZMA2, BCJ, …), no password, a wrong password, a truncated set, or no
 *   video → [ArchiveRefusedException], so the release is remembered unplayable and failover moves on.
 * Errors thrown by the volumes themselves (network, missing articles) pass through unchanged. The password is never
 * logged or put in an exception message. Blocking.
 */
object SevenZipArchive {
    private const val HEADER_MEMORY_KIB = 64 * 1024

    fun open(data: JoinedData, password: String?): SevenZipSet = open(data, password, window = null)

    internal fun open(data: JoinedData, password: String?, window: Int?): SevenZipSet {
        val secret = password?.takeIf { it.isNotEmpty() }
        val file = directory(data, secret)
        try {
            val entry = pick(file)
            val layout = SevenZipLayout.of(file, entry) ?: throw ArchiveRefusedException("The 7z archive's layout isn't supported")
            if (!layout.stored) throw ArchiveRefusedException("The 7z archive is compressed (only stored archives can stream)")
            val encrypted = layout.aesProperties != null
            if (encrypted && secret == null) throw ArchiveRefusedException("The archive is password-protected")
            checkBounds(layout, entry.size, data.size())
            val name = entry.name.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            if (!encrypted) {
                val slice = data.slice(layout.packStart + layout.skip, entry.size)
                return SevenZipSet(name, slice, slice)
            }
            val aes = decrypting(data, layout, entry.size, checkNotNull(secret), window)
            return SevenZipSet(name, aes, aes.packed)
        } finally {
            file.close()
        }
    }

    /** Reads the archive's directory; tells a missing or wrong password and a damaged set apart from network trouble. */
    private fun directory(data: JoinedData, password: String?): SevenZFile {
        val channel = SevenZipChannel(data)
        try {
            return BufferedSha256.during {
                SevenZFile.builder()
                    .setSeekableByteChannel(channel)
                    .setMaxMemoryLimitKiB(HEADER_MEMORY_KIB)
                    .apply { if (password != null) setPassword(password) }
                    .get()
            }
        } catch (e: PasswordRequiredException) {
            rethrowSourceFailure(channel)
            throw ArchiveRefusedException("The archive is password-protected")
        } catch (e: IOException) {
            rethrowSourceFailure(channel)
            throw refusal(data, password)
        } catch (e: RuntimeException) {
            rethrowSourceFailure(channel)
            throw refusal(data, password)
        }
    }

    /** A directory that won't parse: with a password, a header that needs one means the password is wrong. */
    private fun refusal(data: JoinedData, password: String?): ArchiveRefusedException {
        if (password != null && headersEncrypted(data)) return ArchiveRefusedException("The 7z archive password is wrong")
        return ArchiveRefusedException("The 7z archive is damaged or incomplete")
    }

    private fun headersEncrypted(data: JoinedData): Boolean {
        val channel = SevenZipChannel(data)
        return try {
            SevenZFile.builder().setSeekableByteChannel(channel).setMaxMemoryLimitKiB(HEADER_MEMORY_KIB).get().close()
            false
        } catch (e: PasswordRequiredException) {
            true
        } catch (e: IOException) {
            rethrowSourceFailure(channel)
            false
        } catch (e: RuntimeException) {
            rethrowSourceFailure(channel)
            false
        }
    }

    private fun rethrowSourceFailure(channel: SevenZipChannel) {
        when (val failure = channel.sourceFailure) {
            null -> Unit
            is ArchiveRefusedException -> throw failure
            is IOException -> throw failure
            is RuntimeException -> throw failure
            else -> throw IOException(failure)
        }
    }

    private fun pick(file: SevenZFile): SevenZArchiveEntry {
        val files = file.entries.filter { !it.isDirectory && !it.isAntiItem && it.hasStream() }
        val entry = files.filter { MediaExtensions.isVideo(it.name.orEmpty()) }.maxByOrNull { it.size }
            ?: throw ArchiveRefusedException(if (files.isEmpty()) "The archive lists no files" else "The archive holds no video")
        if (entry.size <= 0) throw ArchiveRefusedException("The archive's video is empty")
        return entry
    }

    private fun checkBounds(layout: SevenZipEntryLayout, entrySize: Long, archiveSize: Long) {
        val fits = layout.packStart >= 0 && layout.packSize >= 0 && layout.skip >= 0 &&
            layout.skip <= layout.packSize - entrySize
        if (!fits) throw ArchiveRefusedException("The 7z archive is damaged or incomplete")
        if (layout.packStart > archiveSize - layout.packSize) throw ArchiveRefusedException("A volume of the archive is missing")
    }

    private fun decrypting(data: JoinedData, layout: SevenZipEntryLayout, size: Long, password: String, window: Int?): SevenZipAesData {
        val properties = layout.aesProperties ?: throw ArchiveRefusedException("The 7z archive's layout isn't supported")
        if (layout.packSize % SevenZipAesParams.BLOCK != 0L) throw ArchiveRefusedException("The 7z archive is damaged or incomplete")
        val params = SevenZipAesParams.parse(properties)
        val key = SevenZipAesParams.deriveKey(params, password)
        val packed = data.slice(layout.packStart, layout.packSize)
        val decrypted = try {
            if (window == null) SevenZipAesData(packed, layout.skip, size, key, params.iv)
            else SevenZipAesData(packed, layout.skip, size, key, params.iv, window)
        } finally {
            key.fill(0)
        }
        // Headers in the clear don't prove the password: the video's first bytes must decrypt to a known container.
        val head = decrypted.readUpTo(0, minOf(size, ContentSniffer.HEAD_BYTES.toLong()).toInt())
        if (ContentSniffer.sniff(head) == MediaContainer.UNKNOWN) {
            decrypted.close()
            throw ArchiveRefusedException("The 7z archive password is wrong")
        }
        return decrypted
    }
}
