package com.banupham.appviewcamera.viewer

import com.banupham.appviewcamera.viewer.api.GatewayJsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayJsonParserTest {
    @Test
    fun parsesCameraConfigurationWithoutCredentials() {
        val cameras = GatewayJsonParser.cameras(
            """[{"id":"camera01","name":"Cửa trước","host":"192.168.1.7","port":554,"username":"admin","main_path":"Streaming/Channels/101","sub_path":"Streaming/Channels/102","relay_path":"camera01","enabled":true,"record_enabled":true,"motion_enabled":false}]"""
        )
        assertEquals(1, cameras.size)
        assertEquals("192.168.1.7", cameras.single().host)
        assertEquals("camera01", cameras.single().relayPath)
    }

    @Test
    fun parsesDiscoveryScanResponse() {
        val candidates = GatewayJsonParser.candidates(
            """{"count":2,"candidates":[{"host":"192.168.1.7","port":554,"source":"tcp_scan"},{"host":"192.168.1.7","port":8000,"source":"tcp_scan"}]}"""
        )
        assertEquals(listOf(554, 8000), candidates.map { it.port })
    }
}
