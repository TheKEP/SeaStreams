package com.seastreams.tv.data.backup

import androidx.room.withTransaction
import com.seastreams.tv.core.database.SeaStreamsDatabase
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** Runs a restore's Room writes as one transaction (a seam so JVM tests can use in-memory DAOs). */
interface BackupTransactor {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

class RoomBackupTransactor @Inject constructor(private val database: SeaStreamsDatabase) : BackupTransactor {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction { block() }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {
    @Binds
    abstract fun bindBackupTransactor(impl: RoomBackupTransactor): BackupTransactor
}
