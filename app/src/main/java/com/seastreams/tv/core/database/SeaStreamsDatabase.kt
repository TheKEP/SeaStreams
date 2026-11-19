package com.seastreams.tv.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteTable
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import com.seastreams.tv.core.database.dao.AddonDao
import com.seastreams.tv.core.database.dao.LastUsedStreamDao
import com.seastreams.tv.core.database.dao.LibraryDao
import com.seastreams.tv.core.database.dao.ProfileDao
import com.seastreams.tv.core.database.dao.WatchProgressDao
import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.database.entity.LastUsedStreamEntity
import com.seastreams.tv.core.database.entity.LibraryItemEntity
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.database.entity.WatchProgressEntity

@Database(
    entities = [
        ProfileEntity::class,
        LibraryItemEntity::class,
        WatchProgressEntity::class,
        AddonEntity::class,
        LastUsedStreamEntity::class,
    ],
    // v5 scopes addons per profile: manual ProfileAddonsMigration (registered in DatabaseModule).
    // v6 adds last_used_streams: manual LastUsedStreamMigration (registered in DatabaseModule).
    version = 6,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = SeaStreamsDatabase.DropCustomCatalogs::class),
        // v3 adds watch_progress.dismissedFromContinueWatching (default 0).
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4, spec = SeaStreamsDatabase.DropCacheEntries::class),
    ],
)
abstract class SeaStreamsDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun libraryDao(): LibraryDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun addonDao(): AddonDao
    abstract fun lastUsedStreamDao(): LastUsedStreamDao

    /** v2 removed user-assigned MDBList catalog rows; MDBList is now a one-time Library import only. */
    @DeleteTable(tableName = "custom_catalogs")
    class DropCustomCatalogs : AutoMigrationSpec

    /** v4 removed the unused key/value cache; TMDB/MDBList caching is OkHttp's disk cache (`HttpCachePolicy`). */
    @DeleteTable(tableName = "cache_entries")
    class DropCacheEntries : AutoMigrationSpec

    companion object {
        const val NAME = "seastreams.db"
    }
}
