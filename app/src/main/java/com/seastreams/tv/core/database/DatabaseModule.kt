package com.seastreams.tv.core.database

import android.content.Context
import androidx.room.Room
import com.seastreams.tv.core.database.dao.AddonDao
import com.seastreams.tv.core.database.dao.LastUsedStreamDao
import com.seastreams.tv.core.database.dao.LibraryDao
import com.seastreams.tv.core.database.dao.NewznabIndexerDao
import com.seastreams.tv.core.database.dao.ProfileDao
import com.seastreams.tv.core.database.dao.UsenetProviderDao
import com.seastreams.tv.core.database.dao.WatchProgressDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SeaStreamsDatabase =
        Room.databaseBuilder(context, SeaStreamsDatabase::class.java, SeaStreamsDatabase.NAME)
            .addMigrations(ProfileAddonsMigration, LastUsedStreamMigration, UsenetSourcesMigration)
            .build()

    @Provides
    fun provideProfileDao(db: SeaStreamsDatabase): ProfileDao = db.profileDao()

    @Provides
    fun provideLibraryDao(db: SeaStreamsDatabase): LibraryDao = db.libraryDao()

    @Provides
    fun provideWatchProgressDao(db: SeaStreamsDatabase): WatchProgressDao = db.watchProgressDao()

    @Provides
    fun provideAddonDao(db: SeaStreamsDatabase): AddonDao = db.addonDao()

    @Provides
    fun provideLastUsedStreamDao(db: SeaStreamsDatabase): LastUsedStreamDao = db.lastUsedStreamDao()

    @Provides
    fun provideUsenetProviderDao(db: SeaStreamsDatabase): UsenetProviderDao = db.usenetProviderDao()

    @Provides
    fun provideNewznabIndexerDao(db: SeaStreamsDatabase): NewznabIndexerDao = db.newznabIndexerDao()
}
