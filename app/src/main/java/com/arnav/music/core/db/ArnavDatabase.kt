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
    version = 1,
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
        fun build(context: Context): ArnavDatabase =
            Room.databaseBuilder(context, ArnavDatabase::class.java, NAME)
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()
    }
}
