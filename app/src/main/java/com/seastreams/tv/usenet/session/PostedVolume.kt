package com.seastreams.tv.usenet.session

import com.seastreams.tv.usenet.archive.RarVolumeInfo
import com.seastreams.tv.usenet.archive.RarVolumeParser
import com.seastreams.tv.usenet.archive.NameProbe
import com.seastreams.tv.usenet.archive.VolumeData
import com.seastreams.tv.usenet.archive.readUpTo
import com.seastreams.tv.usenet.stream.UsenetFile
import com.seastreams.tv.usenet.verify.ContentSniffer

/**
 * One posted file of a release as the archive layer sees it. A middle volume of a set may borrow [sibling]'s size
 * and article grid ([UsenetFile.adoptGrid]), so seeking into it needs no probe of its first article.
 */
class PostedVolume(val file: UsenetFile, override var name: String) : VolumeData, NameProbe {
    @Volatile
    var sibling: PostedVolume? = null

    override val statedBytes: Long get() = file.file.statedBytes

    override val subjectName: String? get() = file.file.filename

    override fun size(): Long {
        if (!file.opened) {
            val model = sibling
            if (model != null) file.adoptGrid(model.file) else file.open()
        }
        return file.size
    }

    override fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (!file.opened) size()
        return file.read(position, target, offset, length)
    }

    override fun yencName(): String {
        file.open()
        return file.name
    }

    override fun head(): ByteArray = readUpTo(0, ContentSniffer.HEAD_BYTES)

    override fun rarInfo(): RarVolumeInfo = RarVolumeParser.parse(this)
}
