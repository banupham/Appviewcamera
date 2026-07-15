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
            """{"enabled":true,"local_retention_minutes":60,"clip_count":1,"disk_free_bytes":5000,"disk_total_bytes":10000,"upload_counts":{"PENDING":1,"FAILED":0,"UPLOADED":2}}"""
        )
        val clips = GatewayJsonParser.recordings(
            """{"count":1,"clips":[{"id":"abc","camera_id":"camera01","started_at_ms":1000,"duration_ms":60000,"size_bytes":1234,"local_state":"MISSING","upload_state":"UPLOADED"}]}"""
        )
        assertEquals(true, status.enabled)
        assertEquals(60_000L, clips.single().durationMs)
        assertEquals("camera01", clips.single().cameraId)
        assertEquals(1, status.pendingUploads)
        assertEquals("UPLOADED", clips.single().uploadState)
    }

    @Test
    fun parsesStorageEstimate() {
        val summary = GatewayJsonParser.storageSummary(
            """{"drive_count":2,"online_drive_count":1,"total_bytes":10000,"used_bytes":4000,"free_bytes":6000,"average_bitrate_bps":800000,"estimated_daily_bytes":8640000000,"estimated_retention_seconds":7200,"collecting_statistics":false}"""
        )
        assertEquals(2, summary.driveCount)
        assertEquals(800_000L, summary.averageBitrateBps)
        assertEquals(7_200L, summary.estimatedRetentionSeconds)
    }

    @Test
    fun parsesDriveOAuthWithoutToken() {
        val session = GatewayJsonParser.driveOAuthSession(
            """{"session_id":"s1","remote_id":"drive01","display_name":"Drive 01","status":"WAITING_BROWSER","authorization_url":"https://accounts.google.com/example","error":null}"""
        )
        val proxy = GatewayJsonParser.oauthProxyResponse(
            """{"status":302,"headers":{"Location":"https://example.com"},"body_base64":"b2s="}"""
        )

        assertEquals("s1", session.sessionId)
        assertEquals("WAITING_BROWSER", session.status)
        assertEquals(302, proxy.status)
        assertEquals("ok", proxy.body.toString(Charsets.UTF_8))
    }

    @Test
    fun parsesIndexedPlaybackWithDriveReadyBeforeYoutube() {
        val items = GatewayJsonParser.playbackTimeline(
            """{"camera_id":"camera01","day":"2026-07-15","items":[{"id":"clip01","camera_id":"camera01","start_time":1000,"end_time":61000,"duration":60000,"size_bytes":1234,"motion":true,"protected":false,"local_available":false,"drive_available":true,"youtube_available":false,"youtube_video_id":null,"youtube_start_offset_seconds":0,"status":"READY","preferred_source":"DRIVE_READY","last_error":null}]}"""
        )
        assertEquals(1, items.size)
        assertEquals("DRIVE_READY", items.single().preferredSource)
        assertEquals(true, items.single().playable)
        assertEquals(false, items.single().youtubeAvailable)
    }

    @Test
    fun parsesYoutubeSourceWithExactOffsetAndNoReadySourceDisablesPlayback() {
        val sources = GatewayJsonParser.playbackSources(
            """{"item_id":"clip01","preferred_source":"YOUTUBE_READY","sources":[{"type":"YOUTUBE_READY","state":"READY","stream_url":null,"video_id":"private-video","start_offset_seconds":125,"watch_url":"https://www.youtube.com/watch?v=private-video&t=125s","requires_google_sign_in":true}]}"""
        )
        val unavailable = GatewayJsonParser.playbackItem(
            """{"id":"clip02","camera_id":"camera01","start_time":1000,"end_time":61000,"duration":60000,"size_bytes":1,"motion":false,"protected":false,"local_available":false,"drive_available":false,"youtube_available":false,"youtube_video_id":null,"youtube_start_offset_seconds":0,"status":"PROCESSING","preferred_source":null,"last_error":null}"""
        )
        assertEquals(125, sources.sources.single().startOffsetSeconds)
        assertEquals(true, sources.sources.single().requiresGoogleSignIn)
        assertEquals(false, unavailable.playable)
    }
}
