package com.banupham.appviewcamera.viewer.api

interface GatewayApi {
    suspend fun status(): GatewayStatus
    suspend fun cameras(): List<CameraSummary>
    suspend fun scanCameras(): List<DiscoveryCandidate>
    suspend fun discoveryCandidates(): List<DiscoveryCandidate>
    suspend fun saveCamera(camera: CameraMutation): CameraSummary
    suspend fun deleteCamera(cameraId: String)
}
