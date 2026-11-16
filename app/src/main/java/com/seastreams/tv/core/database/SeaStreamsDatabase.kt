package com.seastreams.tv.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.seastreams.tv.core.database.dao.AddonDao
import com.seastreams.tv.core.database.dao.CacheDao
import com.seastreams.tv.core.database.dao.CatalogDao
import com.seastreams.tv.core.database.dao.LibraryDao
import com.seastreams.tv.core.database.dao.ProfileDao
import com.seastreams.tv.core.database.dao.WatchProgressDao
import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.database.entity.CacheEntryEntity
import com.seastreams.tv.core.database.entity.CustomCatalogEntity
import com.seastreams.tv.core.database.entity.LibraryItemEntity
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.database.entity.WatchProgressEntity

@Database(
    entities = [
        ProfileEntity::class,
        LibraryItemEntity::class,
        WatchProgressEntity::class,
        AddonEntity::class,
        CustomCatalogEntity::class,
        CacheEntryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SeaStreamsDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun libraryDao(): LibraryDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun addonDao(): AddonDao
    abstract fun catalogDao(): CatalogDao
    abstract fun cacheDao(): CacheDao

    companion object {
        const val NAME = "seastreams.db"
    }
}
