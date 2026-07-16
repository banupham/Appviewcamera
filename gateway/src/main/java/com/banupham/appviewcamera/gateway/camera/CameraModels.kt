package com.banupham.appviewcamera.gateway.camera

enum class CameraConnectionStatus {
    OFFLINE, CONNECTING, CONNECTED, RECONNECTING
}

data class Camera(
    val id: Long,
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
    val connectionStatus: CameraConnectionStatus,
    val codec: String?,
    val width: Int?,
    val height: Int?,
    val fps: Float?,
    val bitrate: Int?,
    val lastError: String?,
    val lastTestedAt: Long?,
    val createdAt: Long
)

data class CameraDraft(
    val id: Long = 0,
    val name: String = "",
    val ip: String = "",
    val port: String = "554",
    val username: String = "",
    val password: String = "",
    val mainRtspUrl: String = "",
    val subRtspUrl: String = "",
    val relayPath: String = "",
    val enabled: Boolean = true,
    val recordEnabled: Boolean = true,
    val motionEnabled: Boolean = false,
    val audioEnabled: Boolean = false
)
