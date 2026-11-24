package com.seastreams.tv.usenet.archive

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile

/**
 * Where a stored 7z entry's bytes sit in the archive (ARCHITECTURE §10): its folder's single pack stream
 * ([packStart], [packSize]) and the entry's offset inside the unpacked folder ([skip], non-zero only for a solid
 * folder). [aesProperties] is the `7zAES` coder's property blob when the folder is encrypted. [stored] is `false` when
 * any other coder (LZMA, LZMA2, BCJ, Deflate, …) or more than one pack stream is involved: the bytes aren't the video.
 */
internal class SevenZipEntryLayout(
    val packStart: Long,
    val packSize: Long,
    val skip: Long,
    val aesProperties: ByteArray?,
    val stored: Boolean,
)

/**
 * Reads [SevenZipEntryLayout] from `SevenZFile`'s parsed directory. commons-compress has no public API for pack
 * offsets, so this reads its package-private model (`Archive`, `StreamMap`, `Folder`, `Coder`) and is **pinned to
 * commons-compress 1.28.0** (ARCHITECTURE §4): the 7z fixture tests fail if a bump moves a field. Any surprise
 * (a missing field, an impossible value) answers `null`, and the archive is refused rather than guessed at.
 * (`SevenZArchiveEntry.getContentMethods` is only filled once an entry is read, so the coders are read here too.) The release build is not minified; enabling R8 needs keep rules for these classes.
 */
internal object SevenZipLayout {
    /** `SevenZFile.SIGNATURE_HEADER_SIZE`: pack positions count from the end of the 32-byte start header. */
    private const val SIGNATURE_HEADER_SIZE = 32L
    private val COPY = byteArrayOf(0x00)
    private val AES = byteArrayOf(0x06, 0xF1.toByte(), 0x07, 0x01)

    fun of(file: SevenZFile, entry: SevenZArchiveEntry): SevenZipEntryLayout? = try {
        resolve(file, entry)
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: ClassCastException) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    } catch (e: ArithmeticException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private fun resolve(file: SevenZFile, entry: SevenZArchiveEntry): SevenZipEntryLayout? {
        val archive = field(file, "archive")
        val files = field(archive, "files") as Array<*>
        val index = files.indexOfFirst { it === entry }
        if (index < 0) return null
        val map = field(archive, "streamMap")
        val fileFolders = field(map, "fileFolderIndex") as IntArray
        val folderIndex = fileFolders[index]
        if (folderIndex < 0) return null
        val folder = (field(archive, "folders") as Array<*>)[folderIndex] ?: return null
        var stored = (field(folder, "packedStreams") as LongArray).size == 1
        var aes: ByteArray? = null
        val coders = field(folder, "coders") as Array<*>
        if (coders.isEmpty()) return null
        for (item in coders) {
            val coder = item ?: return null
            if (field(coder, "numInStreams") != 1L || field(coder, "numOutStreams") != 1L) stored = false
            val method = field(coder, "decompressionMethodId") as ByteArray
            when {
                method.contentEquals(COPY) -> Unit
                method.contentEquals(AES) && aes == null -> aes = (field(coder, "properties") as ByteArray).clone()
                else -> stored = false
            }
        }
        val firstPack = (field(map, "folderFirstPackStreamIndex") as IntArray)[folderIndex]
        val packPos = field(archive, "packPos") as Long
        val packOffset = (field(map, "packStreamOffsets") as LongArray)[firstPack]
        val start = Math.addExact(SIGNATURE_HEADER_SIZE, Math.addExact(packPos, packOffset))
        val packSize = (field(archive, "packSizes") as LongArray)[firstPack]
        var skip = 0L
        for (i in 0 until index) {
            if (fileFolders[i] == folderIndex) skip = Math.addExact(skip, (files[i] as SevenZArchiveEntry).size)
        }
        return SevenZipEntryLayout(start, packSize, skip, aes, stored)
    }

    private fun field(owner: Any, name: String): Any {
        val field = owner.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(owner) ?: throw NoSuchFieldException("$name is null")
    }
}
