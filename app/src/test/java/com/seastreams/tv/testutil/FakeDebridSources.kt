package com.seastreams.tv.testutil

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.seastreams.tv.core.database.dao.DebridProviderDao
import com.seastreams.tv.core.database.entity.DebridProviderEntity
import com.seastreams.tv.data.debrid.DebridServiceKind
import com.seastreams.tv.data.debrid.auth.DeviceCode
import com.seastreams.tv.data.debrid.auth.DeviceFlow
import com.seastreams.tv.data.debrid.auth.DevicePoll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** In-memory `debrid_providers`, enforcing the unique `providerType` like the real table. */
class FakeDebridProviderDao : DebridProviderDao {
    val rows = MutableStateFlow<List<DebridProviderEntity>>(emptyList())
    private var nextId = 1L

    private fun sorted() = rows.value.sortedWith(compareBy({ it.sortOrder }, { it.id }))

    private fun edit(id: Long, change: (DebridProviderEntity) -> DebridProviderEntity) {
        rows.value = rows.value.map { if (it.id == id) change(it) else it }
    }

    override fun observeAll(): Flow<List<DebridProviderEntity>> = rows.map { sorted() }

    override suspend fun getAll(): List<DebridProviderEntity> = sorted()

    override suspend fun get(id: Long): DebridProviderEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun getByType(providerType: String): DebridProviderEntity? = rows.value.firstOrNull { it.providerType == providerType }

    override suspend fun nextSortOrder(): Int = (rows.value.maxOfOrNull { it.sortOrder } ?: -1) + 1

    override suspend fun insert(provider: DebridProviderEntity): Long {
        check(rows.value.none { it.providerType == provider.providerType }) { "UNIQUE constraint failed: providerType" }
        val id = nextId++
        rows.value = rows.value + provider.copy(id = id)
        return id
    }

    override suspend fun update(provider: DebridProviderEntity) = edit(provider.id) { provider }

    override suspend fun setEnabled(id: Long, enabled: Boolean) = edit(id) { it.copy(enabled = enabled) }

    override suspend fun setSignInNeeded(id: Long, signInNeeded: Boolean) = edit(id) { it.copy(signInNeeded = signInNeeded) }

    override suspend fun updateTokens(id: Long, credentialCipher: String, refreshTokenCipher: String?, expiresAtMillis: Long?) =
        edit(id) { it.copy(credentialCipher = credentialCipher, refreshTokenCipher = refreshTokenCipher, expiresAtMillis = expiresAtMillis, signInNeeded = false) }

    override suspend fun updateSortOrder(id: Long, sortOrder: Int) = edit(id) { it.copy(sortOrder = sortOrder) }

    override suspend fun delete(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

/** A `DataStore<Preferences>` held in memory: edits apply on the caller's coroutine, so virtual time just works. */
class InMemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val next = transform(state.value)
        state.value = next
        return next
    }

    val keys: Set<String> get() = state.value.asMap().keys.map { it.name }.toSet()
}

/**
 * A scripted [DeviceFlow]: [start] returns a code (or throws [startError]); each [poll] takes the next entry of
 * [polls] (an exception is thrown), then stays [DevicePoll.Pending].
 */
class ScriptedDeviceFlow(
    override val kind: DebridServiceKind,
    private val polls: ArrayDeque<Any> = ArrayDeque(),
    private val intervalSeconds: Int = 5,
    private val expiresInSeconds: Int = 600,
) : DeviceFlow {
    var starts = 0
    var pollCount = 0
    var startError: Exception? = null

    override suspend fun start(): DeviceCode {
        startError?.let { throw it }
        starts++
        return DeviceCode("device-secret-$starts", "CODE$starts", "https://example.com/device", intervalSeconds, expiresInSeconds)
    }

    override suspend fun poll(code: DeviceCode): DevicePoll {
        pollCount++
        return when (val next = polls.removeFirstOrNull() ?: DevicePoll.Pending) {
            is Exception -> throw next
            is DevicePoll -> next
            else -> error("Unexpected script entry $next")
        }
    }
}

/** A [Clock] reading the test scheduler's virtual time. */
class VirtualClock(private val scheduler: TestCoroutineScheduler) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(scheduler.currentTime)
}
