package com.seastreams.tv.usenet.session

import com.seastreams.tv.domain.usenet.UsenetPerformance
import com.seastreams.tv.usenet.archive.UsenetUnplayableException
import com.seastreams.tv.usenet.nntp.NntpFuture
import com.seastreams.tv.usenet.nntp.monotonicMillis
import com.seastreams.tv.usenet.nzb.NzbFile
import com.seastreams.tv.usenet.verify.CensusReport
import com.seastreams.tv.usenet.verify.CensusVerifier
import com.seastreams.tv.usenet.verify.DamageVerdict
import com.seastreams.tv.usenet.verify.ReleaseHealth
import com.seastreams.tv.usenet.verify.ReleaseHealthCache
import java.io.Closeable

/**
 * Runs the census for a new session (PRD §12 *Verification*): it waits up to *Verify budget* for a verdict before
 * playback starts (a broken release is refused at once, so failover moves on before anything plays), then keeps
 * going in the background with *Census background concurrency* for at most *Census max lifetime*. A broken verdict
 * is remembered for the next search; it also stops the session if it lands within [earlyFailMs] of opening — after
 * that, damage is left to the read that meets it (Strict fails there, Tolerant fills it).
 */
class SessionVerification(
    private val health: ReleaseHealthCache,
    private val performance: UsenetPerformance,
    private val clock: () -> Long = monotonicMillis,
    private val earlyFailMs: Long = EARLY_FAIL_MS,
) {
    /** Starts the census; returns what cancels it with the session, or throws when the budget already proved damage. */
    fun start(session: UsenetSession, files: List<NzbFile>, stat: (String) -> NntpFuture<Boolean>): Closeable? {
        if (files.isEmpty()) return null
        val openedAt = clock()
        val census = CensusVerifier(
            stat = stat,
            concurrency = performance.censusConcurrency,
            maxLifetimeMs = performance.censusMaxLifetimeMinutes * MINUTE_MS,
            policy = performance.damagePolicy,
            clock = clock,
        ).start(files)
        val budget = performance.verifyBudgetMillis.toLong()
        if (budget > 0) {
            val early = census.await(budget)
            val verdict = early?.verdict
            if (verdict is DamageVerdict.Broken) {
                health.record(session.token, ReleaseHealth.Broken(verdict.reason))
                census.cancel()
                throw UsenetUnplayableException(verdict.reason)
            }
        }
        census.onFinished { report -> finished(session, report, openedAt) }
        return Closeable { census.cancel() }
    }

    private fun finished(session: UsenetSession, report: CensusReport, openedAt: Long) {
        when (val verdict = report.verdict) {
            is DamageVerdict.Broken -> {
                health.record(session.token, ReleaseHealth.Broken(verdict.reason))
                if (clock() - openedAt <= earlyFailMs) session.fail(UsenetUnplayableException(verdict.reason))
            }
            DamageVerdict.Intact, is DamageVerdict.Repairable -> if (report.complete) health.record(session.token, ReleaseHealth.Healthy)
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val EARLY_FAIL_MS = 60_000L
    }
}
