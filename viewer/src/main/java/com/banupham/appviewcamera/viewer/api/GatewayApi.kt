package com.banupham.appviewcamera.viewer.api

interface GatewayApi {
    suspend fun status(): GatewayStatus
    suspend fun cameras(): List<CameraSummary>
    suspend fun scanCameras(): List<DiscoveryCandidate>
    suspend fun discoveryCandidates(): List<DiscoveryCandidate>
    suspend fun saveCamera(camera: CameraMutation): CameraSummary
    suspend fun deleteCamera(cameraId: String)
    suspend fun drives(): List<GoogleDriveAccount>
    suspend fun addDrive(drive: GoogleDriveMutation): GoogleDriveAccount
    suspend fun refreshDrive(driveId: String): GoogleDriveAccount
    suspend fun deleteDrive(driveId: String)
    suspend fun recordingStatus(): RecordingStatus
    suspend fun updateRecording(enabled: Boolean, localRetentionMinutes: Int): RecordingStatus
    suspend fun recordings(cameraId: String? = null, fromMs: Long? = null, toMs: Long? = null): List<RecordingClip>
}
