package com.seastreams.tv.core.database

import android.content.Context
import androidx.room.Room
import com.seastreams.tv.core.database.dao.AddonDao
import com.seastreams.tv.core.database.dao.CacheDao
import com.seastreams.tv.core.database.dao.LibraryDao
import com.seastreams.tv.core.database.dao.ProfileDao
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
        Room.databaseBuilder(context, SeaStreamsDatabase::class.java, SeaStreamsDatabase.NAME).build()

    @Provides
    fun provideProfileDao(db: SeaStreamsDatabase): ProfileDao = db.profileDao()

    @Provides
    fun provideLibraryDao(db: SeaStreamsDatabase): LibraryDao = db.libraryDao()

    @Provides
    fun provideWatchProgressDao(db: SeaStreamsDatabase): WatchProgressDao = db.watchProgressDao()

    @Provides
    fun provideAddonDao(db: SeaStreamsDatabase): AddonDao = db.addonDao()

    @Provides
    fun provideCacheDao(db: SeaStreamsDatabase): CacheDao = db.cacheDao()
}
