package com.banupham.appviewcamera.gateway.rtsp

import com.banupham.appviewcamera.gateway.camera.Camera

data class CameraProbeResult(
    val codec: String,
    val width: Int?,
    val height: Int?,
    val fps: Float?,
    val bitrate: Int?
)

interface CameraProbe {
    suspend fun probe(
        camera: Camera,
        onRetry: (attempt: Int, delayMillis: Long) -> Unit = { _, _ -> }
    ): CameraProbeResult
}
