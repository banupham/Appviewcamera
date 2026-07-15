package com.banupham.appviewcamera.viewer

import com.banupham.appviewcamera.viewer.api.CameraSummary
import com.banupham.appviewcamera.viewer.api.PlaybackItem
import com.banupham.appviewcamera.viewer.settings.GatewayCollection
import com.banupham.appviewcamera.viewer.settings.GatewayConfig
import com.banupham.appviewcamera.viewer.settings.PairingUriParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayCollectionTest {
    private val home = gateway("home", "Gateway Nhà", "192.168.1.2")
    private val warehouse = gateway("warehouse", "Gateway Kho", "100.64.0.8")

    @Test
    fun addsTwoGatewaysAndSwitchesWithoutOverwritingEither() {
        val collection = GatewayCollection().upsert(home).upsert(warehouse)

        assertEquals(2, collection.gateways.size)
        assertEquals("warehouse", collection.currentGatewayId)
        assertEquals("home", collection.select("home").currentGatewayId)
        assertEquals("192.168.1.2", collection.select("home").current?.host)
    }

    @Test
    fun duplicateQrNeedsExplicitUpsertAndDoesNotCreateAThirdGateway() {
        val original = GatewayCollection().upsert(home).upsert(warehouse)
        val incoming = PairingUriParser.parse(
            "appviewcamera://pair?gateway_id=home&host=100.70.0.2&api_port=8080&rtsp_port=8554&token=new-secret"
        ).getOrThrow()

        assertEquals("192.168.1.2", original.gateways.first { it.id == incoming.id }.host)
        val confirmed = original.upsert(incoming)
        assertEquals(2, confirmed.gateways.size)
        assertEquals("100.70.0.2", confirmed.current?.host)
    }

    @Test
    fun switchingGatewayClearsCameraPlaybackAndStorageScopedState() {
        val first = GatewayCollection().upsert(home)
        val second = first.upsert(warehouse)
        val dirty = ViewerUiState(
            config = home,
            gateways = first.gateways,
            currentGatewayId = "home",
            cameras = listOf(camera("home-camera")),
            playbackItems = listOf(playback("home-clip")),
            selectedCameraId = "home-camera",
            selectedPlaybackItemId = "home-clip"
        )

        val switched = dirty.activateGateway(second)

        assertEquals("warehouse", switched.currentGatewayId)
        assertTrue(switched.cameras.isEmpty())
        assertTrue(switched.playbackItems.isEmpty())
        assertNull(switched.selectedCameraId)
        assertNull(switched.selectedPlaybackItemId)
    }

    @Test
    fun offlineGatewayDoesNotChangeOtherGatewayStatus() {
        val collection = GatewayCollection()
            .upsert(home.copy(status = "ONLINE"))
            .upsert(warehouse.copy(status = "ONLINE"))
            .updateStatus("warehouse", "OFFLINE")

        assertEquals("ONLINE", collection.gateways.first { it.id == "home" }.status)
        assertEquals("OFFLINE", collection.gateways.first { it.id == "warehouse" }.status)
    }

    private fun gateway(id: String, name: String, host: String) = GatewayConfig(
        id = id,
        name = name,
        host = host,
        apiPort = 8080,
        rtspPort = 8554,
        apiToken = "secret"
    ).validate().getOrThrow()

    private fun camera(id: String) = CameraSummary(
        id, id, "192.0.2.1", 554, "admin", "main", "sub", id,
        enabled = true, recordingEnabled = true, motionEnabled = false
    )

    private fun playback(id: String) = PlaybackItem(
        id, "home-camera", 1, 2, 1, 1, false, false,
        localAvailable = true, driveAvailable = false, youtubeAvailable = false,
        youtubeVideoId = null, youtubeStartOffsetSeconds = 0,
        status = "READY", preferredSource = "LOCAL_CACHE", lastError = null
    )
}
