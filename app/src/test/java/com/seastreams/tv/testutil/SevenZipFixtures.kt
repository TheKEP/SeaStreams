package com.seastreams.tv.testutil

/** The synthetic 7z archives under `src/test/resources/usenet/sevenzip/` (see the README there). */
object SevenZipFixtures {
    const val PASSWORD = "fixture-only"
    const val VIDEO_NAME = "movie.mkv"

    fun bytes(name: String): ByteArray =
        checkNotNull(SevenZipFixtures::class.java.getResourceAsStream("/usenet/sevenzip/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }

    /** `movie.mkv` inside every fixture: the EBML magic, then 100 000 patterned bytes. */
    fun payload(): ByteArray {
        val out = ByteArray(4 + 100_000)
        byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()).copyInto(out)
        for (i in 0 until 100_000) out[4 + i] = ((i * 31 + i / 257) % 256).toByte()
        return out
    }

    val SPLIT = listOf("stored-split.7z.001", "stored-split.7z.002", "stored-split.7z.003")
}
