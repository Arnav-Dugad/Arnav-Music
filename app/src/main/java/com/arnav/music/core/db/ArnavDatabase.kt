package com.arnav.music.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class, LikeEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class,
        PlayEventEntity::class, SearchCacheEntity::class, RecentSearchEntity::class, AiCacheEntity::class,
        KvSyncEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ArnavDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun likes(): LikeDao
    abstract fun playlists(): PlaylistDao
    abstract fun events(): PlayEventDao
    abstract fun search(): SearchDao
    abstract fun aiCache(): AiCacheDao
    abstract fun kv(): KvSyncDao

    companion object {
        const val NAME = "arnav-music.db"

        /** v2: song/video variant + parsed credits for YouTube uploads. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN variant TEXT")
                db.execSQL("ALTER TABLE tracks ADD COLUMN credits TEXT")
                db.execSQL("ALTER TABLE tracks ADD COLUMN compilation INTEGER NOT NULL DEFAULT 0")
            }
        }
        fun build(context: Context): ArnavDatabase =
            Room.databaseBuilder(context, ArnavDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()
    }
}
