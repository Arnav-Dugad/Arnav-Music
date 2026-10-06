package com.arnav.music.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class, LikeEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class,
        PlayEventEntity::class, SearchCacheEntity::class, RecentSearchEntity::class, AiCacheEntity::class,
        KvSyncEntity::class, LyricsEntity::class, AudioFeaturesEntity::class, PendingMatchEntity::class, ImportHistoryEntity::class,
    ],
    version = 4,
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
    abstract fun lyrics(): LyricsDao
    abstract fun audioFeatures(): AudioFeaturesDao
    abstract fun pendingMatches(): PendingMatchDao
    abstract fun importHistory(): ImportHistoryDao

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
        /** v3: on-device lyrics, local audio analysis, Spotify/CSV import queue. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `lyrics` (`trackId` TEXT NOT NULL, `text` TEXT NOT NULL, `synced` INTEGER NOT NULL, `source` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`trackId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `audio_features` (`trackId` TEXT NOT NULL, `bpm` REAL NOT NULL, `beatOffsetMs` INTEGER NOT NULL, `loudnessDb` REAL NOT NULL, `energy` REAL NOT NULL, `envelope` BLOB NOT NULL, `analyzedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, `ok` INTEGER NOT NULL, PRIMARY KEY(`trackId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `pending_matches` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT, `durationMs` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `failed` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_matches_playlistId` ON `pending_matches` (`playlistId`)")
            }
        }
        /** v4: musical key + intro/outro points for local songs; import history (undo/redo). */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `audio_features` ADD COLUMN `musicalKey` INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE `audio_features` ADD COLUMN `introMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `audio_features` ADD COLUMN `outroMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `import_history` (`id` TEXT NOT NULL, `source` TEXT NOT NULL, `label` TEXT NOT NULL, `playlistIds` TEXT NOT NULL, `songCount` INTEGER NOT NULL, `matchedCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `undone` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }
        fun build(context: Context): ArnavDatabase =
            Room.databaseBuilder(context, ArnavDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()
    }
}
