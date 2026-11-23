package com.seastreams.tv.testutil

import com.seastreams.tv.core.database.dao.NewznabIndexerDao
import com.seastreams.tv.core.database.dao.UsenetProviderDao
import com.seastreams.tv.core.database.entity.NewznabIndexerEntity
import com.seastreams.tv.core.database.entity.UsenetProviderEntity
import com.seastreams.tv.core.security.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Reversible, clearly-marked stand-in for the Keystore cipher; [broken] makes every decrypt fail (lost key). */
class FakeSecretCipher : SecretCipher {
    var broken = false

    override fun encrypt(plain: String): String = "enc:" + plain.reversed()

    override fun decrypt(stored: String): String? =
        if (broken || !stored.startsWith("enc:")) null else stored.removePrefix("enc:").reversed()
}

class FakeUsenetProviderDao : UsenetProviderDao {
    val rows = MutableStateFlow<List<UsenetProviderEntity>>(emptyList())
    private var nextId = 1L

    private fun sorted() = rows.value.sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override fun observeAll(): Flow<List<UsenetProviderEntity>> = rows.map { sorted() }

    override suspend fun getAll(): List<UsenetProviderEntity> = sorted()

    override suspend fun get(id: Long): UsenetProviderEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun nextSortOrder(): Int = (rows.value.maxOfOrNull { it.sortOrder } ?: -1) + 1

    override suspend fun insert(provider: UsenetProviderEntity): Long {
        val id = nextId++
        rows.value = rows.value + provider.copy(id = id)
        return id
    }

    override suspend fun update(provider: UsenetProviderEntity) {
        rows.value = rows.value.map { if (it.id == provider.id) provider else it }
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        rows.value = rows.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    override suspend fun updateSortOrder(id: Long, sortOrder: Int) {
        rows.value = rows.value.map { if (it.id == id) it.copy(sortOrder = sortOrder) else it }
    }

    override suspend fun delete(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

class FakeNewznabIndexerDao : NewznabIndexerDao {
    val rows = MutableStateFlow<List<NewznabIndexerEntity>>(emptyList())
    private var nextId = 1L

    private fun sorted() = rows.value.sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override fun observeAll(): Flow<List<NewznabIndexerEntity>> = rows.map { sorted() }

    override suspend fun getAll(): List<NewznabIndexerEntity> = sorted()

    override suspend fun get(id: Long): NewznabIndexerEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun nextSortOrder(): Int = (rows.value.maxOfOrNull { it.sortOrder } ?: -1) + 1

    override suspend fun insert(indexer: NewznabIndexerEntity): Long {
        val id = nextId++
        rows.value = rows.value + indexer.copy(id = id)
        return id
    }

    override suspend fun update(indexer: NewznabIndexerEntity) {
        rows.value = rows.value.map { if (it.id == indexer.id) indexer else it }
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        rows.value = rows.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    override suspend fun updateSortOrder(id: Long, sortOrder: Int) {
        rows.value = rows.value.map { if (it.id == id) it.copy(sortOrder = sortOrder) else it }
    }

    override suspend fun delete(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}
