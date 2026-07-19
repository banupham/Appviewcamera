package com.banupham.appviewcamera.gateway

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.banupham.appviewcamera.gateway.database.GatewayDatabase
import com.banupham.appviewcamera.gateway.camera.CameraRepository
import com.banupham.appviewcamera.gateway.rtsp.Media3RtspCameraProbe
import com.banupham.appviewcamera.gateway.recording.RecordingRepository
import com.banupham.appviewcamera.gateway.recording.RecordingSettingsStore
import com.banupham.appviewcamera.gateway.security.AndroidKeystoreCredentialCipher
import com.banupham.appviewcamera.gateway.server.GatewayRuntimeState
import com.banupham.appviewcamera.gateway.server.GatewaySettingsStore

class GatewayApplication : Application() {
    val container: GatewayContainer by lazy { GatewayContainer(this) }
}

class GatewayContainer(application: Application) {
    private val database = GatewayDatabase.create(application)
    private val credentialCipher = AndroidKeystoreCredentialCipher()

    val cameraRepository = CameraRepository(database.cameraDao(), credentialCipher)
    val recordingRepository = RecordingRepository(application, database.recordingClipDao())
    val recordingSettings = RecordingSettingsStore(application)
    @UnstableApi
    val cameraProbe = Media3RtspCameraProbe(application, credentialCipher)
    val gatewaySettings = GatewaySettingsStore(application)
    val gatewayRuntimeState = GatewayRuntimeState()
}
