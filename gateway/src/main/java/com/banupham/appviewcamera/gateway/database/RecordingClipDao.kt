package com.banupham.appviewcamera.gateway.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface RecordingClipDao {
    @Query("SELECT * FROM recording_clips WHERE id = :id LIMIT 1")
    suspend fun get(id: String): RecordingClipEntity?

    @Query("SELECT * FROM recording_clips WHERE relativePath = :relativePath LIMIT 1")
    suspend fun getByPath(relativePath: String): RecordingClipEntity?

    @Query(
        """
        SELECT * FROM recording_clips
        WHERE clipState != 'RECORDING'
          AND (:cameraId IS NULL OR cameraId = :cameraId)
          AND (:fromMs IS NULL OR startedAtMs >= :fromMs)
          AND (:toMs IS NULL OR startedAtMs < :toMs)
        ORDER BY startedAtMs DESC
        LIMIT :limit
        """
    )
    suspend fun list(cameraId: String?, fromMs: Long?, toMs: Long?, limit: Int): List<RecordingClipEntity>

    @Query(
        """
        SELECT * FROM recording_clips
        WHERE cameraId = :cameraId AND clipState != 'RECORDING'
          AND startedAtMs >= :fromMs AND startedAtMs < :toMs
        ORDER BY startedAtMs ASC
        LIMIT :limit
        """
    )
    suspend fun timeline(cameraId: String, fromMs: Long, toMs: Long, limit: Int): List<RecordingClipEntity>

    @Query("SELECT * FROM recording_clips WHERE cameraId = :cameraId AND clipState != 'RECORDING' ORDER BY startedAtMs DESC LIMIT :limit")
    suspend fun recentForDays(cameraId: String, limit: Int): List<RecordingClipEntity>

    @Query("SELECT * FROM recording_clips WHERE localState = 'AVAILABLE' ORDER BY startedAtMs ASC")
    suspend fun localClipsOldestFirst(): List<RecordingClipEntity>

    @Query(
        "SELECT * FROM recording_clips WHERE localState = 'AVAILABLE' AND " +
            "(uploadState = 'PENDING' OR (uploadState = 'FAILED' AND nextRetryMs <= :nowMs)) " +
            "AND clipState != 'RECORDING' ORDER BY startedAtMs ASC LIMIT :limit"
    )
    suspend fun uploadCandidates(nowMs: Long, limit: Int): List<RecordingClipEntity>

    @Query("SELECT COUNT(*) FROM recording_clips WHERE clipState != 'RECORDING'")
    suspend fun clipCount(): Int

    @Query("SELECT COUNT(*) FROM recording_clips WHERE uploadState = :state AND clipState != 'RECORDING'")
    suspend fun uploadCount(state: String): Int

    @Query(
        """
        UPDATE recording_clips SET uploadState = 'UPLOADED', clipState = 'LOCAL_CACHE',
            remoteId = :remoteId, remotePath = :remotePath, remoteFileId = :remoteFileId,
            remoteSizeBytes = :remoteSizeBytes, remoteVerifiedAtMs = :verifiedAtMs,
            uploadedAtMs = :verifiedAtMs, lastError = NULL, stateUpdatedAtMs = :verifiedAtMs
        WHERE id = :id
        """
    )
    suspend fun markDriveUploaded(
        id: String,
        remoteId: String,
        remotePath: String,
        remoteFileId: String,
        remoteSizeBytes: Long,
        verifiedAtMs: Long
    ): Int

    @Query(
        "UPDATE recording_clips SET uploadState = 'FAILED', clipState = 'UPLOAD_RETRY', lastError = :error, " +
            "uploadAttempts = uploadAttempts + 1, nextRetryMs = :nextRetryMs, stateUpdatedAtMs = :nowMs WHERE id = :id"
    )
    suspend fun markDriveFailed(id: String, error: String, nextRetryMs: Long, nowMs: Long): Int

    @Upsert
    suspend fun upsert(clip: RecordingClipEntity)

    @Query("UPDATE recording_clips SET protected = :isProtected, stateUpdatedAtMs = :nowMs WHERE id = :id")
    suspend fun setProtected(id: String, isProtected: Boolean, nowMs: Long): Int

    @Query(
        """
        UPDATE recording_clips SET
            localState = 'DELETED', localDeletedAtMs = :nowMs,
            clipState = CASE WHEN youtubeStatus = 'YOUTUBE_READY' THEN 'YOUTUBE_READY' ELSE 'DRIVE_READY' END,
            stateUpdatedAtMs = :nowMs
        WHERE id = :id
        """
    )
    suspend fun markLocalDeleted(id: String, nowMs: Long)

    @Query(
        "UPDATE recording_clips SET localState = 'AVAILABLE', localCachedAtMs = :nowMs, " +
            "localDeletedAtMs = NULL, clipState = 'LOCAL_CACHE', stateUpdatedAtMs = :nowMs WHERE id = :id"
    )
    suspend fun markLocalRestored(id: String, nowMs: Long): Int

    @Query("UPDATE recording_clips SET localState = 'MISSING', stateUpdatedAtMs = :nowMs WHERE id = :id AND localState = 'AVAILABLE'")
    suspend fun markMissing(id: String, nowMs: Long)
}
