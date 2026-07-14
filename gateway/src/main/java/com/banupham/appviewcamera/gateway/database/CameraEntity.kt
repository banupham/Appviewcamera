package com.banupham.appviewcamera.gateway.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cameras",
    indices = [
        Index(value = ["relayPath"], unique = true),
        Index(value = ["enabled"]),
        Index(value = ["createdAt"])
    ]
)
data class CameraEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val ip: String,
    val port: Int,
    val username: String,
    val encryptedPassword: String,
    val mainRtspUrl: String,
    val subRtspUrl: String,
    val relayPath: String,
    val enabled: Boolean,
    val recordEnabled: Boolean,
    val motionEnabled: Boolean,
    val audioEnabled: Boolean,
    val connectionStatus: String,
    val codec: String?,
    val width: Int?,
    val height: Int?,
    val fps: Float?,
    val bitrate: Int?,
    val lastError: String?,
    val lastTestedAt: Long?,
    val createdAt: Long
)
