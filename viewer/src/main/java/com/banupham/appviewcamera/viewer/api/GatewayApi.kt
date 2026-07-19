package com.banupham.appviewcamera.viewer.api

interface GatewayApi {
    suspend fun status(): GatewayStatus
    suspend fun cameras(): List<CameraSummary>
    suspend fun scanCameras(): List<DiscoveryCandidate>
    suspend fun discoveryCandidates(): List<DiscoveryCandidate>
    suspend fun saveCamera(camera: CameraMutation): CameraSummary
    suspend fun deleteCamera(cameraId: String)
    suspend fun drives(): List<GoogleDriveAccount>
    suspend fun storageSummary(): StorageSummary
    suspend fun addDrive(drive: GoogleDriveMutation): GoogleDriveAccount
    suspend fun configureDriveOAuth(clientId: String, clientSecret: String)
    suspend fun startDriveOAuth(remoteId: String, displayName: String): DriveOAuthSession
    suspend fun driveOAuthStatus(sessionId: String): DriveOAuthSession
    suspend fun forwardDriveOAuthCallback(sessionId: String, path: String): OAuthProxyResponse
    suspend fun refreshDrive(driveId: String): GoogleDriveAccount
    suspend fun activateDrive(driveId: String): GoogleDriveAccount
    suspend fun deleteDrive(driveId: String)
    suspend fun youtubeAccounts(): List<YouTubeAccount>
    suspend fun youtubeStatus(): YouTubeArchiveStatus
    suspend fun configureYouTube(clientId: String, clientSecret: String, targetDurationMinutes: Int): YouTubeArchiveStatus
    suspend fun startYouTubeOAuth(accountId: String, displayName: String): YouTubeOAuthSession
    suspend fun reconnectYouTube(accountId: String): YouTubeOAuthSession
    suspend fun youtubeOAuthStatus(sessionId: String): YouTubeOAuthSession
    suspend fun forwardYouTubeOAuthCallback(sessionId: String, path: String): OAuthProxyResponse
    suspend fun deleteYouTubeAccount(accountId: String)
    suspend fun recordingStatus(): RecordingStatus
    suspend fun updateRecording(enabled: Boolean, localRetentionMinutes: Int): RecordingStatus
    suspend fun recordings(cameraId: String? = null, fromMs: Long? = null, toMs: Long? = null): List<RecordingClip>
    suspend fun protectRecording(recordingId: String, protected: Boolean)
    suspend fun playbackDays(cameraId: String): List<PlaybackDay>
    suspend fun playbackTimeline(cameraId: String, fromMs: Long, toMs: Long): List<PlaybackItem>
    suspend fun playbackItem(itemId: String): PlaybackItem
    suspend fun playbackSources(itemId: String): PlaybackSources
}
