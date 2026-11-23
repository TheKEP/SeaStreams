package com.seastreams.tv.data.debrid.auth

import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.debrid.DebridCredentialState
import com.seastreams.tv.data.debrid.DebridServiceKind
import com.seastreams.tv.data.debrid.DebridSourcesRepository
import com.seastreams.tv.data.debrid.api.DebridRateLimitedException
import com.seastreams.tv.data.debrid.api.DebridServiceException
import com.seastreams.tv.testutil.FakeDebridProviderDao
import com.seastreams.tv.testutil.FakeSecretCipher
import com.seastreams.tv.testutil.ScriptedDeviceFlow
import com.seastreams.tv.testutil.VirtualClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/** The code sign-in state machine (PRD §14) on virtual time: pending → linked / expired / denied / cancelled. */
class DeviceAuthSessionsTest {

    private val dao = FakeDebridProviderDao()
    private val repository = DebridSourcesRepository(dao, FakeSecretCipher(), Dispatchers.Unconfined)

    private fun TestScope.sessions(vararg flows: ScriptedDeviceFlow) = DeviceAuthSessions(
        flows = DeviceFlowProvider { kind -> flows.firstOrNull { it.kind == kind } },
        repository = repository,
        scope = backgroundScope,
        clock = VirtualClock(testScheduler),
    )

    private fun flow(vararg polls: Any, kind: DebridServiceKind = DebridServiceKind.TORBOX, expiresIn: Int = 600) =
        ScriptedDeviceFlow(kind, ArrayDeque(polls.toList()), intervalSeconds = 5, expiresInSeconds = expiresIn)

    private fun TestScope.tick(seconds: Int) {
        advanceTimeBy(seconds * 1_000L)
        runCurrent()
    }

    @Test
    fun `a pending code links, the account appears and the handle is never the device code`() = runTest {
        val tokens = DeviceTokens("tb-access-token-1")
        val torBox = flow(DevicePoll.Pending, DevicePoll.Linked(tokens))
        val sessions = sessions(torBox)

        val session = sessions.start(DebridServiceKind.TORBOX)
        assertEquals(DeviceAuthStatus.PENDING, session.status)
        assertEquals("CODE1", session.userCode)
        assertTrue(session.id.matches(Regex("[0-9a-f]{32}")))
        assertFalse(session.id.contains("device-secret"))
        assertFalse(session.toString().contains("device-secret"))

        tick(5)
        assertEquals(DeviceAuthStatus.PENDING, sessions.status(session.id)?.status)
        tick(5)
        assertEquals(DeviceAuthStatus.LINKED, sessions.status(session.id)?.status)
        val provider = repository.providers.first().single()
        assertEquals(DebridServiceKind.TORBOX to DebridCredentialState.SIGNED_IN, provider.kind to provider.credentialState)
        assertEquals("tb-access-token-1", repository.credentials(provider.id).accessToken)
        tick(30)
        assertEquals(2, torBox.pollCount, "polling stops once linked")
    }

    @Test
    fun `a code that runs out expires, and the service can also say so or deny it`() = runTest {
        val sessions = sessions(
            flow(expiresIn = 12),
            flow(DevicePoll.Expired, kind = DebridServiceKind.REAL_DEBRID),
            flow(DevicePoll.Pending, DevicePoll.Denied, kind = DebridServiceKind.ALL_DEBRID),
        )
        val timeout = sessions.start(DebridServiceKind.TORBOX)
        val expired = sessions.start(DebridServiceKind.REAL_DEBRID)
        val denied = sessions.start(DebridServiceKind.ALL_DEBRID)

        tick(10)
        assertEquals(DeviceAuthStatus.PENDING, sessions.status(timeout.id)?.status)
        assertEquals(DeviceAuthStatus.EXPIRED, sessions.status(expired.id)?.status)
        assertEquals(DeviceAuthStatus.DENIED, sessions.status(denied.id)?.status)
        tick(5)
        assertEquals(DeviceAuthStatus.EXPIRED, sessions.status(timeout.id)?.status)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `cancelling stops polling and a completion afterwards is ignored`() = runTest {
        val torBox = flow(DevicePoll.Linked(DeviceTokens("late-token-123")))
        val sessions = sessions(torBox)
        val session = sessions.start(DebridServiceKind.TORBOX)

        assertTrue(sessions.cancel(session.id))
        tick(30)
        assertEquals(DeviceAuthStatus.CANCELLED, sessions.status(session.id)?.status)
        assertEquals(0, torBox.pollCount)
        assertTrue(dao.rows.value.isEmpty())
        assertFalse(sessions.cancel(session.id), "already finished")
    }

    @Test
    fun `starting again for the same service supersedes the previous code`() = runTest {
        val torBox = flow(DevicePoll.Pending, DevicePoll.Linked(DeviceTokens("tb-token-second")))
        val sessions = sessions(torBox)
        val first = sessions.start(DebridServiceKind.TORBOX)
        val second = sessions.start(DebridServiceKind.TORBOX)

        assertNotEquals(first.id, second.id)
        assertEquals(DeviceAuthStatus.CANCELLED, sessions.status(first.id)?.status)
        tick(10)
        assertEquals(DeviceAuthStatus.LINKED, sessions.status(second.id)?.status)
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `a code that completes after Clear Debrid settings or a removal is discarded`() = runTest {
        val torBox = flow(DevicePoll.Pending, DevicePoll.Linked(DeviceTokens("tb-after-clear")))
        val realDebrid = flow(DevicePoll.Pending, DevicePoll.Linked(DeviceTokens("rd-after-remove")), kind = DebridServiceKind.REAL_DEBRID)
        val sessions = sessions(torBox, realDebrid)
        val rd = repository.saveKey(null, DebridServiceKind.REAL_DEBRID, "rd-old-key-0123", null)

        val cleared = sessions.start(DebridServiceKind.TORBOX)
        val removed = sessions.start(DebridServiceKind.REAL_DEBRID)
        tick(5)
        repository.deleteAll() // what Clear Debrid settings does, minus the session cancel
        tick(5)

        assertEquals(DeviceAuthStatus.CANCELLED, sessions.status(cleared.id)?.status)
        assertEquals(DeviceAuthStatus.CANCELLED, sessions.status(removed.id)?.status)
        assertTrue(dao.rows.value.isEmpty(), "nothing came back after the clear (rd=$rd)")
    }

    @Test
    fun `cancelAll stops every pending sign-in`() = runTest {
        val sessions = sessions(flow(), flow(kind = DebridServiceKind.ALL_DEBRID))
        val a = sessions.start(DebridServiceKind.TORBOX)
        val b = sessions.start(DebridServiceKind.ALL_DEBRID)
        sessions.cancelAll()
        assertEquals(listOf(DeviceAuthStatus.CANCELLED, DeviceAuthStatus.CANCELLED), listOf(a, b).map { sessions.status(it.id)?.status })
    }

    @Test
    fun `network trouble keeps polling, rate limiting backs off, a refusal fails`() = runTest {
        val flaky = flow(IOException("offline"), DebridRateLimitedException(), DevicePoll.Linked(DeviceTokens("tb-after-retry")))
        val refused = flow(DebridServiceException(), kind = DebridServiceKind.ALL_DEBRID)
        val sessions = sessions(flaky, refused)
        val ok = sessions.start(DebridServiceKind.TORBOX)
        val failed = sessions.start(DebridServiceKind.ALL_DEBRID)

        tick(10) // IOException at 5 s, 429 at 10 s → the next poll waits 10 s
        assertEquals(DeviceAuthStatus.FAILED, sessions.status(failed.id)?.status)
        tick(5)
        assertEquals(2, flaky.pollCount)
        tick(5)
        assertEquals(DeviceAuthStatus.LINKED, sessions.status(ok.id)?.status)
    }

    @Test
    fun `a service without a code sign-in, or one that won't start, is a readable error`() = runTest {
        val broken = flow().apply { startError = IOException("down") }
        val sessions = sessions(broken)
        assertEquals(
            "Premiumize has no code sign-in here. Paste your API key instead",
            assertThrows<UserFacingException> { sessions.start(DebridServiceKind.PREMIUMIZE) }.message,
        )
        assertEquals("Can't reach TorBox", assertThrows<UserFacingException> { sessions.start(DebridServiceKind.TORBOX) }.message)
    }
}
