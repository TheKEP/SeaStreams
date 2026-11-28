package com.seastreams.tv.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteTable
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import com.seastreams.tv.core.database.dao.AddonDao
import com.seastreams.tv.core.database.dao.DebridProviderDao
import com.seastreams.tv.core.database.dao.LastUsedStreamDao
import com.seastreams.tv.core.database.dao.LibraryDao
import com.seastreams.tv.core.database.dao.NewznabIndexerDao
import com.seastreams.tv.core.database.dao.NextUpDismissalDao
import com.seastreams.tv.core.database.dao.ProfileDao
import com.seastreams.tv.core.database.dao.UsenetProviderDao
import com.seastreams.tv.core.database.dao.WatchProgressDao
import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.database.entity.DebridProviderEntity
import com.seastreams.tv.core.database.entity.LastUsedStreamEntity
import com.seastreams.tv.core.database.entity.LibraryItemEntity
import com.seastreams.tv.core.database.entity.NewznabIndexerEntity
import com.seastreams.tv.core.database.entity.NextUpDismissalEntity
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.database.entity.UsenetProviderEntity
import com.seastreams.tv.core.database.entity.WatchProgressEntity

@Database(
    entities = [
        ProfileEntity::class,
        LibraryItemEntity::class,
        WatchProgressEntity::class,
        AddonEntity::class,
        LastUsedStreamEntity::class,
        UsenetProviderEntity::class,
        NewznabIndexerEntity::class,
        DebridProviderEntity::class,
        NextUpDismissalEntity::class,
    ],
    // v5 scopes addons per profile: manual ProfileAddonsMigration (registered in DatabaseModule).
    // v6 adds last_used_streams: manual LastUsedStreamMigration (registered in DatabaseModule).
    // v7 adds the app-wide usenet_providers + newznab_indexers: manual UsenetSourcesMigration.
    // v8 adds the app-wide debrid_providers: manual DebridSourcesMigration.
    // v9 stores last_used_streams.streamUrl as a SHA-256 fingerprint: manual LastUsedStreamUrlMigration.
    // v10 adds next_up_dismissals: manual NextUpDismissalsMigration.
    // v11 adds library_items.keywordIds: manual LibraryKeywordsMigration.
    version = 11,
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
    abstract fun usenetProviderDao(): UsenetProviderDao
    abstract fun newznabIndexerDao(): NewznabIndexerDao
    abstract fun debridProviderDao(): DebridProviderDao
    abstract fun nextUpDismissalDao(): NextUpDismissalDao

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
