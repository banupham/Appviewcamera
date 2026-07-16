package com.banupham.appviewcamera.gateway.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CameraEntity::class, RecordingClipEntity::class], version = 2, exportSchema = false)
abstract class GatewayDatabase : RoomDatabase() {
    abstract fun cameraDao(): CameraDao
    abstract fun recordingClipDao(): RecordingClipDao

    companion object {
        fun create(context: Context): GatewayDatabase = Room.databaseBuilder(
            context.applicationContext,
            GatewayDatabase::class.java,
            "camera_gateway.db"
        ).addMigrations(MIGRATION_1_2).build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recording_clips` (
                        `id` TEXT NOT NULL, `cameraId` TEXT NOT NULL, `relativePath` TEXT NOT NULL,
                        `startedAtMs` INTEGER NOT NULL, `durationMs` INTEGER, `sizeBytes` INTEGER NOT NULL,
                        `modifiedMs` INTEGER NOT NULL, `clipState` TEXT NOT NULL, `localState` TEXT NOT NULL,
                        `uploadState` TEXT NOT NULL, `remoteId` TEXT, `remotePath` TEXT, `remoteFileId` TEXT,
                        `remoteSizeBytes` INTEGER, `remoteVerifiedAtMs` INTEGER, `youtubeStatus` TEXT NOT NULL,
                        `youtubeVideoId` TEXT, `youtubeBatchId` TEXT, `youtubeStartOffsetSeconds` INTEGER NOT NULL,
                        `youtubeEndOffsetSeconds` INTEGER NOT NULL, `youtubeUpdatedAtMs` INTEGER,
                        `youtubeLastError` TEXT, `uploadAttempts` INTEGER NOT NULL, `nextRetryMs` INTEGER NOT NULL,
                        `lastError` TEXT, `uploadedAtMs` INTEGER, `localCachedAtMs` INTEGER,
                        `localDeletedAtMs` INTEGER, `stateUpdatedAtMs` INTEGER NOT NULL,
                        `protected` INTEGER NOT NULL, `motion` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_recording_clips_relativePath` ON `recording_clips` (`relativePath`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_recording_clips_cameraId_startedAtMs` ON `recording_clips` (`cameraId`, `startedAtMs`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_recording_clips_clipState_nextRetryMs_startedAtMs` ON `recording_clips` (`clipState`, `nextRetryMs`, `startedAtMs`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_recording_clips_youtubeBatchId_youtubeStatus_cameraId_startedAtMs` ON `recording_clips` (`youtubeBatchId`, `youtubeStatus`, `cameraId`, `startedAtMs`)")
            }
        }
    }
}
