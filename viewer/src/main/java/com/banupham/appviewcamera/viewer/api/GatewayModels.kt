package com.banupham.appviewcamera.viewer.api

data class CameraSummary(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val mainPath: String,
    val subPath: String,
    val relayPath: String,
    val enabled: Boolean,
    val recordingEnabled: Boolean,
    val motionEnabled: Boolean
)

data class DiscoveryCandidate(
    val host: String,
    val port: Int,
    val source: String
)

data class CameraMutation(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String?,
    val mainPath: String,
    val subPath: String,
    val relayPath: String,
    val enabled: Boolean = true,
    val recordEnabled: Boolean = true,
    val motionEnabled: Boolean = false,
    val audioEnabled: Boolean = true
)

data class GatewayStatus(
    val status: String,
    val mediaMtxState: String,
    val cameraCount: Int
)

data class DriveQuota(
    val total: Long?,
    val used: Long?,
    val free: Long?
)

data class GoogleDriveAccount(
    val id: String,
    val displayName: String,
    val active: Boolean,
    val configured: Boolean,
    val status: String,
    val lastError: String?,
    val quota: DriveQuota?
)

data class GoogleDriveMutation(
    val id: String,
    val displayName: String,
    val oauthToken: String
)
