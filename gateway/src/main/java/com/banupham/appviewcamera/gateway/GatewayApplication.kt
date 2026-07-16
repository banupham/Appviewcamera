package com.banupham.appviewcamera.gateway

import android.app.Application
import com.banupham.appviewcamera.gateway.database.GatewayDatabase
import com.banupham.appviewcamera.gateway.camera.CameraRepository
import com.banupham.appviewcamera.gateway.rtsp.Media3RtspCameraProbe
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
    val cameraProbe = Media3RtspCameraProbe(application, credentialCipher)
    val gatewaySettings = GatewaySettingsStore(application)
    val gatewayRuntimeState = GatewayRuntimeState()
}
