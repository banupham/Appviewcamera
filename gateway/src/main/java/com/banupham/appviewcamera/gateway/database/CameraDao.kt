package com.banupham.appviewcamera.gateway.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CameraDao {
    @Query("SELECT * FROM cameras ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<CameraEntity>>

    @Query("SELECT * FROM cameras WHERE id = :id")
    suspend fun get(id: Long): CameraEntity?

    @Upsert
    suspend fun upsert(camera: CameraEntity): Long

    @Query("DELETE FROM cameras WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query(
        """
        UPDATE cameras SET
            connectionStatus = :status,
            codec = :codec,
            width = :width,
            height = :height,
            fps = :fps,
            bitrate = :bitrate,
            lastError = :lastError,
            lastTestedAt = :testedAt
        WHERE id = :id
        """
    )
    suspend fun updateProbeResult(
        id: Long,
        status: String,
        codec: String?,
        width: Int?,
        height: Int?,
        fps: Float?,
        bitrate: Int?,
        lastError: String?,
        testedAt: Long?
    )
}
