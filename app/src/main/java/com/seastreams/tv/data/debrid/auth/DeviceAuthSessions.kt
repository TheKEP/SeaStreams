package com.seastreams.tv.data.debrid.auth

import com.seastreams.tv.core.util.ApplicationScope
import com.seastreams.tv.core.util.toHex
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.debrid.DebridServiceKind
import com.seastreams.tv.data.debrid.DebridSourcesRepository
import com.seastreams.tv.data.debrid.api.DebridException
import com.seastreams.tv.data.debrid.api.DebridRateLimitedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.SecureRandom
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Where a code sign-in stands (PRD §14 *Device code sign-in*). */
enum class DeviceAuthStatus { PENDING, LINKED, EXPIRED, DENIED, CANCELLED, FAILED }

/** A code sign-in as the portal shows it. [id] is a random 128-bit handle, never the service's device code. */
data class DeviceAuthSession(
    val id: String,
    val kind: DebridServiceKind,
    val userCode: String,
    val verificationUrl: String,
    val expiresAtMillis: Long,
    val status: DeviceAuthStatus,
)

/** Looks up the code sign-in a service offers right now. */
fun interface DeviceFlowProvider {
    fun flow(kind: DebridServiceKind): DeviceFlow?
}

/**
 * Pending code sign-ins (ARCHITECTURE §12): at most one per service, each polled on [scope] at the service's
 * interval until it is linked, expired, denied or cancelled. Starting a new sign-in for a service cancels the
 * previous one. A completed code is saved only if the service's credential generation is unchanged since the start
 * ([DebridSourcesRepository.saveDeviceSignIn]): one that lands after the account was removed or `Clear Debrid
 * settings` ran is discarded (and reads as cancelled). Network trouble while polling is retried until the code
 * expires; rate limiting doubles the interval.
 */
@Singleton
class DeviceAuthSessions @Inject constructor(
    private val flows: DeviceFlowProvider,
    private val repository: DebridSourcesRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val clock: Clock,
) {
    private class Entry(@Volatile var session: DeviceAuthSession) {
        var job: Job? = null
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private val random = SecureRandom()

    /** Asks [kind] for a code and starts polling it; the previous sign-in for [kind] is cancelled. */
    suspend fun start(kind: DebridServiceKind): DeviceAuthSession {
        val flow = flows.flow(kind) ?: throw UserFacingException("${kind.displayName} has no code sign-in here. Paste your ${kind.keyLabel} instead")
        cancelPending { it.kind == kind }
        val generation = repository.generation(kind)
        val code = try {
            flow.start()
        } catch (e: IOException) {
            throw UserFacingException("Can't reach ${kind.displayName}")
        } catch (e: DebridRateLimitedException) {
            throw UserFacingException("Rate limited — try again shortly")
        } catch (e: DebridException) {
            throw UserFacingException("${kind.displayName} didn't give a sign-in code. Try again")
        }
        val entry = Entry(
            DeviceAuthSession(
                id = newId(),
                kind = kind,
                userCode = code.userCode,
                verificationUrl = code.verificationUrl,
                expiresAtMillis = clock.millis() + code.expiresInSeconds * MILLIS_PER_SECOND,
                status = DeviceAuthStatus.PENDING,
            ),
        )
        synchronized(lock) {
            // Another start for the same service may have finished while this one waited for its code.
            entries.values.filter { it.session.kind == kind && it.session.status == DeviceAuthStatus.PENDING }.forEach(::cancelEntry)
            entries[entry.session.id] = entry
            trim()
            entry.job = scope.launch { poll(entry, flow, code, generation) }
        }
        return entry.session
    }

    fun status(id: String): DeviceAuthSession? = synchronized(lock) { entries[id]?.session }

    /** Cancels one sign-in; returns whether it was still pending. */
    fun cancel(id: String): Boolean = cancelPending { it.id == id }

    /** `Clear Debrid settings`: every pending sign-in stops (and any that still completes is discarded). */
    fun cancelAll() {
        cancelPending { true }
    }

    private fun cancelPending(predicate: (DeviceAuthSession) -> Boolean): Boolean = synchronized(lock) {
        val pending = entries.values.filter { it.session.status == DeviceAuthStatus.PENDING && predicate(it.session) }
        pending.forEach(::cancelEntry)
        pending.isNotEmpty()
    }

    private fun cancelEntry(entry: Entry) {
        entry.session = entry.session.copy(status = DeviceAuthStatus.CANCELLED)
        entry.job?.cancel()
    }

    private suspend fun poll(entry: Entry, flow: DeviceFlow, code: DeviceCode, generation: Long) {
        var intervalMillis = code.intervalSeconds * MILLIS_PER_SECOND
        while (true) {
            delay(intervalMillis)
            if (clock.millis() >= entry.session.expiresAtMillis) return finish(entry, DeviceAuthStatus.EXPIRED)
            val result = try {
                flow.poll(code)
            } catch (e: IOException) {
                continue
            } catch (e: DebridRateLimitedException) {
                intervalMillis = (intervalMillis * 2).coerceAtMost(MAX_INTERVAL_MILLIS)
                continue
            } catch (e: DebridException) {
                return finish(entry, DeviceAuthStatus.FAILED)
            }
            when (result) {
                DevicePoll.Pending -> Unit
                DevicePoll.Expired -> return finish(entry, DeviceAuthStatus.EXPIRED)
                DevicePoll.Denied -> return finish(entry, DeviceAuthStatus.DENIED)
                is DevicePoll.Linked -> {
                    if (entry.session.status != DeviceAuthStatus.PENDING) return
                    val saved = repository.saveDeviceSignIn(flow.kind, result.tokens, generation, clock.millis())
                    return finish(entry, if (saved != null) DeviceAuthStatus.LINKED else DeviceAuthStatus.CANCELLED)
                }
            }
        }
    }

    private fun finish(entry: Entry, status: DeviceAuthStatus) = synchronized(lock) {
        if (entry.session.status == DeviceAuthStatus.PENDING) entry.session = entry.session.copy(status = status)
    }

    /** Keeps the newest [MAX_SESSIONS]; finished ones go first. */
    private fun trim() {
        while (entries.size > MAX_SESSIONS) {
            val victim = entries.values.firstOrNull { it.session.status != DeviceAuthStatus.PENDING } ?: entries.values.first()
            cancelEntry(victim)
            entries.remove(victim.session.id)
        }
    }

    private fun newId(): String {
        return ByteArray(ID_BYTES).also(random::nextBytes).toHex()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val MAX_INTERVAL_MILLIS = 60_000L
        const val MAX_SESSIONS = 16
        const val ID_BYTES = 16
    }
}
