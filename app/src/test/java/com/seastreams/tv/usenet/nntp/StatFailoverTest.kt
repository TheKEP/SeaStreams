package com.seastreams.tv.usenet.nntp

import com.seastreams.tv.testutil.FakeNntpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The census STAT fails over like BODY: a primary's 430 asks the backup before calling an article missing. */
class StatFailoverTest {
    private val primary = FakeNntpServer()
    private val backup = FakeNntpServer()
    private val engine = NntpEngine(DefaultSocketOpener, monotonicMillis, null).apply {
        configure(
            listOf(primary.endpoint("P", id = 1), backup.endpoint("B", isBackup = true, id = 2)),
            NntpEngineSettings(dialTimeoutMs = 2_000, stallTimeoutMs = 2_000, dialBackoffMs = 20),
        )
    }

    @AfterEach
    fun tearDown() {
        engine.close()
        primary.close()
        backup.close()
    }

    @Test
    fun `an article only the backup has is present`() {
        backup.put("only-backup@x", "body".toByteArray())
        assertTrue(engine.stat("only-backup@x").await(5_000))
    }

    @Test
    fun `an article nobody has is missing`() {
        assertFalse(engine.stat("gone@x").await(5_000))
    }
}
