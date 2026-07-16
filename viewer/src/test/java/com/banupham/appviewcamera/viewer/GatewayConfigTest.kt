package com.banupham.appviewcamera.viewer

import com.banupham.appviewcamera.viewer.settings.GatewayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayConfigTest {
    @Test
    fun buildsApiAndRelayUrlsFromUserConfiguration() {
        val config = GatewayConfig("192.168.1.2", 8080, 8554, "secret")
        assertTrue(config.validate().isSuccess)
        assertEquals("http://192.168.1.2:8080", config.apiBaseUrl)
        assertEquals("rtsp://192.168.1.2:8554/camera01", config.relayUrl("camera01"))
    }

    @Test
    fun rejectsAFullUrlInHostField() {
        assertTrue(GatewayConfig("http://192.168.1.2", 8080, 8554, "secret").validate().isFailure)
    }

    @Test
    fun bracketsIpv6HostInRelayUrl() {
        val config = GatewayConfig("fd00::20", 8080, 8554, "secret")
        assertEquals("http://[fd00::20]:8080", config.apiBaseUrl)
        assertEquals("rtsp://[fd00::20]:8554/camera01", config.relayUrl("camera01"))
    }
}
