package com.banupham.appviewcamera.gateway.media

import com.banupham.appviewcamera.gateway.camera.Camera
import com.banupham.appviewcamera.gateway.camera.CameraConnectionStatus
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMtxConfigWriterTest {
    @Test
    fun sharesPrimaryIngestForRelayAndRecordingAndUsesRealSubstreamForGrid() {
        val writer = MediaMtxConfigWriter({ "p@ss word" }, File("/recordings"), 8554)

        val root = JSONObject(writer.render(listOf(camera())))
        val paths = root.getJSONObject("paths")
        val primary = paths.getJSONObject("camera01")
        val preview = paths.getJSONObject("camera01_sub")

        assertEquals("rtsp://admin:p%40ss%20word@192.168.1.10:554/main", primary.getString("source"))
        assertFalse(primary.getBoolean("sourceOnDemand"))
        assertTrue(primary.getBoolean("record"))
        assertTrue(primary.getString("recordPath").contains("%path"))
        assertEquals("rtsp://admin:p%40ss%20word@192.168.1.10:554/sub", preview.getString("source"))
        assertTrue(preview.getBoolean("sourceOnDemand"))
    }

    @Test
    fun disablesRecordingGloballyWithoutDisablingRelay() {
        val writer = MediaMtxConfigWriter({ "secret" }, File("/recordings"), 8554) { false }

        val primary = JSONObject(writer.render(listOf(camera())))
            .getJSONObject("paths").getJSONObject("camera01")

        assertFalse(primary.has("record"))
        assertTrue(primary.getBoolean("sourceOnDemand"))
    }

    private fun camera() = Camera(
        id = 1,
        name = "Camera 01",
        ip = "192.168.1.10",
        port = 554,
        username = "admin",
        encryptedPassword = "encrypted",
        mainRtspUrl = "rtsp://192.168.1.10:554/main",
        subRtspUrl = "rtsp://192.168.1.10:554/sub",
        relayPath = "camera01",
        enabled = true,
        recordEnabled = true,
        motionEnabled = false,
        audioEnabled = true,
        connectionStatus = CameraConnectionStatus.CONNECTED,
        codec = "video/avc",
        width = 1920,
        height = 1080,
        fps = 25f,
        bitrate = 4_000_000,
        lastError = null,
        lastTestedAt = null,
        createdAt = 0
    )
}
