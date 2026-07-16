package com.banupham.appviewcamera.gateway.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recording_clips",
    indices = [
        Index(value = ["relativePath"], unique = true),
        Index(value = ["cameraId", "startedAtMs"]),
        Index(value = ["clipState", "nextRetryMs", "startedAtMs"]),
        Index(value = ["youtubeBatchId", "youtubeStatus", "cameraId", "startedAtMs"])
    ]
)
data class RecordingClipEntity(
    @PrimaryKey val id: String,
    val cameraId: String,
    val relativePath: String,
    val startedAtMs: Long,
    val durationMs: Long?,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val clipState: String = "LOCAL_PENDING",
    val localState: String = "AVAILABLE",
    val uploadState: String = "PENDING",
    val remoteId: String? = null,
    val remotePath: String? = null,
    val remoteFileId: String? = null,
    val remoteSizeBytes: Long? = null,
    val remoteVerifiedAtMs: Long? = null,
    val youtubeStatus: String = "NOT_CONFIGURED",
    val youtubeVideoId: String? = null,
    val youtubeBatchId: String? = null,
    val youtubeStartOffsetSeconds: Int = 0,
    val youtubeEndOffsetSeconds: Int = 0,
    val youtubeUpdatedAtMs: Long? = null,
    val youtubeLastError: String? = null,
    val uploadAttempts: Int = 0,
    val nextRetryMs: Long = 0,
    val lastError: String? = null,
    val uploadedAtMs: Long? = null,
    val localCachedAtMs: Long? = null,
    val localDeletedAtMs: Long? = null,
    val stateUpdatedAtMs: Long = 0,
    val protected: Boolean = false,
    val motion: Boolean = false
)
