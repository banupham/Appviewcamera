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

    @Test
    fun parsesDriveQuotaWithoutCredentials() {
        val drives = GatewayJsonParser.drives(
            """[{"id":"drive01","display_name":"Camera backup","active":true,"configured":true,"status":"ONLINE","last_error":null,"quota":{"total":1000,"used":400,"free":600}}]"""
        )
        assertEquals("ONLINE", drives.single().status)
        assertEquals(600L, drives.single().quota?.free)
    }

    @Test
    fun parsesRecordingStatusAndClips() {
        val status = GatewayJsonParser.recordingStatus(
            """{"enabled":true,"local_retention_minutes":60,"clip_count":1,"disk_free_bytes":5000,"disk_total_bytes":10000}"""
        )
        val clips = GatewayJsonParser.recordings(
            """{"count":1,"clips":[{"id":"abc","camera_id":"camera01","started_at_ms":1000,"duration_ms":60000,"size_bytes":1234}]}"""
        )
        assertEquals(true, status.enabled)
        assertEquals(60_000L, clips.single().durationMs)
        assertEquals("camera01", clips.single().cameraId)
    }
}
