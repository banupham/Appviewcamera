package com.banupham.appviewcamera.gateway.camera

import org.junit.Assert.assertTrue
import org.junit.Test

class CameraValidatorTest {
    @Test
    fun acceptsCompleteCameraConfiguration() {
        val errors = CameraValidator.validate(
            CameraDraft(
                name = "Cổng chính",
                ip = "camera.local",
                port = "554",
                mainRtspUrl = "rtsp://camera.local/live/main",
                subRtspUrl = "rtsp://camera.local/live/sub",
                relayPath = "camera01"
            )
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun rejectsInvalidPortUrlAndRelayPath() {
        val errors = CameraValidator.validate(
            CameraDraft(name = "Camera", ip = "host", port = "70000", mainRtspUrl = "http://host", relayPath = "bad path")
        )
        assertTrue(errors.size >= 3)
    }

    @Test
    fun acceptsVendorPathWithoutDuplicatingCameraEndpoint() {
        val errors = CameraValidator.validate(
            CameraDraft(
                name = "Camera",
                ip = "192.168.1.20",
                port = "554",
                mainRtspUrl = "/cam/realmonitor?channel=1&subtype=0",
                relayPath = "camera01"
            )
        )
        assertTrue(errors.isEmpty())
    }
}
