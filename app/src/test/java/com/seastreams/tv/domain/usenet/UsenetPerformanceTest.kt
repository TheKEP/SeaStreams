package com.seastreams.tv.domain.usenet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UsenetPerformanceTest {

    @Test
    fun `defaults are the Conservative template`() {
        assertEquals(UsenetPerformance.template(UsenetProfile.CONSERVATIVE), UsenetPerformance())
        assertEquals(UsenetProfile.CONSERVATIVE, UsenetPerformance().profile)
    }

    @Test
    fun `no template can exhaust a 512 MB Android TV heap`() {
        UsenetProfile.entries.forEach { profile ->
            val t = UsenetPerformance.template(profile)
            assertTrue(t.maxConcurrentDownloads in 1..48, "${profile.label}: bounded downloads, never 0 = auto")
            assertTrue(t.readAheadSegments <= 32, "${profile.label}: read-ahead")
            assertTrue(t.censusConcurrency <= 6, "${profile.label}: census")
        }
    }

    @Test
    fun `templates match the PRD section 12 table`() {
        // readAhead, maxDownloads, diskMb, warm, priority%, segmentTimeout, stall, censusConcurrency, imports
        val expected = mapOf(
            UsenetProfile.CONSERVATIVE to listOf(16, 30, 512, 0, 90, 30, 30, 4, 2),
            UsenetProfile.BALANCED to listOf(24, 36, 1_024, 2, 80, 30, 30, 6, 4),
            UsenetProfile.HIGH to listOf(32, 48, 2_048, 4, 80, 15, 10, 6, 4),
        )
        expected.forEach { (profile, values) ->
            val t = UsenetPerformance.template(profile)
            assertEquals(profile, t.profile)
            assertEquals(
                values,
                listOf(
                    t.readAheadSegments, t.maxConcurrentDownloads, t.segmentDiskCacheMb, t.warmConnections, t.streamingPriorityPercent,
                    t.segmentTimeoutSeconds, t.segmentStallTimeoutSeconds, t.censusConcurrency, t.maxConcurrentImports,
                ),
                profile.label,
            )
            // Shared by every template.
            assertEquals(listOf(15, 60, 3_600, 5, 30, 30, 0, 150), listOf(
                t.dialTimeoutSeconds, t.idleConnectionTtlSeconds, t.streamIdleTimeoutSeconds, t.circuitBreakerThreshold,
                t.circuitBreakerCooldownSeconds, t.censusMaxLifetimeMinutes, t.verifyBudgetMillis, t.maxNzbSizeMb,
            ))
            assertEquals(listOf(true, false, false, true, true), listOf(
                t.lazyRarResolution, t.strictArchiveMembership, t.verifyFileContents, t.verifyArticleChecksums, t.matroskaHoleRepair,
            ))
            assertEquals(VerifyMode.CENSUS, t.verifyMode)
            assertEquals(DamagePolicy.STRICT, t.damagePolicy)
        }
    }

    @Test
    fun `picking a template overwrites every value and Custom keeps them`() {
        val tuned = UsenetPerformance().edited { it.copy(readAheadSegments = 100, lazyRarResolution = false) }
        assertEquals(UsenetPerformance.template(UsenetProfile.HIGH), tuned.withProfile(UsenetProfile.HIGH))
        val custom = tuned.withProfile(UsenetProfile.CUSTOM)
        assertEquals(UsenetProfile.CUSTOM, custom.profile)
        assertEquals(100, custom.readAheadSegments)
        assertEquals(false, custom.lazyRarResolution)
    }

    @Test
    fun `any single edit switches the profile to Custom`() {
        val high = UsenetPerformance.template(UsenetProfile.HIGH)
        val edited = high.edited { it.copy(matroskaHoleRepair = false) }
        assertEquals(UsenetProfile.CUSTOM, edited.profile)
        assertEquals(false, edited.matroskaHoleRepair)
        assertEquals(32, edited.readAheadSegments)
    }

    @Test
    fun `values are clamped to their ranges and warm connections snap to 0, 2 or 4`() {
        val wild = UsenetPerformance(
            readAheadSegments = 999, maxConcurrentDownloads = -1, segmentDiskCacheMb = 100_000, warmConnections = 3,
            streamingPriorityPercent = 10, segmentTimeoutSeconds = 500, segmentStallTimeoutSeconds = 1, dialTimeoutSeconds = 0,
            idleConnectionTtlSeconds = 1, streamIdleTimeoutSeconds = -5, circuitBreakerThreshold = 0, circuitBreakerCooldownSeconds = 9_999,
            verifyBudgetMillis = 9_999, censusConcurrency = 0, censusMaxLifetimeMinutes = 999, maxNzbSizeMb = 1, maxConcurrentImports = 50,
        ).clamped()
        assertEquals(256, wild.readAheadSegments)
        assertEquals(0, wild.maxConcurrentDownloads)
        assertEquals(4_096, wild.segmentDiskCacheMb)
        assertEquals(2, wild.warmConnections)
        assertEquals(50, wild.streamingPriorityPercent)
        assertEquals(120, wild.segmentTimeoutSeconds)
        assertEquals(5, wild.segmentStallTimeoutSeconds)
        assertEquals(5, wild.dialTimeoutSeconds)
        assertEquals(15, wild.idleConnectionTtlSeconds)
        assertEquals(0, wild.streamIdleTimeoutSeconds)
        assertEquals(1, wild.circuitBreakerThreshold)
        assertEquals(300, wild.circuitBreakerCooldownSeconds)
        assertEquals(5_000, wild.verifyBudgetMillis)
        assertEquals(1, wild.censusConcurrency)
        assertEquals(120, wild.censusMaxLifetimeMinutes)
        assertEquals(25, wild.maxNzbSizeMb)
        assertEquals(8, wild.maxConcurrentImports)
        assertEquals(4, UsenetPerformance(warmConnections = 9).clamped().warmConnections)
        assertEquals(0, UsenetPerformance(warmConnections = -3).clamped().warmConnections)
    }
}
