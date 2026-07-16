package com.banupham.appviewcamera.gateway.recording

import com.banupham.appviewcamera.gateway.database.RecordingClipEntity
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPolicyTest {
    @Test
    fun generatesStableTermuxCompatibleClipId() {
        assertEquals(
            RecordingRepository.stableId("camera01/camera01/2026-07-16/2026-07-16_12-30-00-123456.mp4"),
            RecordingRepository.stableId("camera01/camera01/2026-07-16/2026-07-16_12-30-00-123456.mp4")
        )
        assertEquals(24, RecordingRepository.stableId("camera01/test.mp4").length)
    }

    @Test
    fun parsesMediaMtxTimestampAndUsesExactlyOnePercentBudget() {
        assertNotNull(RecordingRepository.parseStartedAt("2026-07-16_12-30-00-123456", ZoneId.of("Asia/Ho_Chi_Minh")))
        assertEquals(10_000L, RecordingRepository.cacheBudget(1_000_000L))
    }

    @Test
    fun neverDeletesOnlyUnverifiedCopyOrProtectedClip() {
        val pending = clip()
        assertFalse(RetentionPolicy.canDeleteLocal(pending))
        assertFalse(RetentionPolicy.canDeleteLocal(pending.copy(protected = true, youtubeStatus = "YOUTUBE_READY", youtubeVideoId = "video")))
        assertTrue(RetentionPolicy.canDeleteLocal(pending.copy(youtubeStatus = "YOUTUBE_READY", youtubeVideoId = "video")))
        assertTrue(RetentionPolicy.canDeleteLocal(pending.copy(
            uploadState = "UPLOADED", remoteId = "drive-1", remoteFileId = "file-1",
            remoteVerifiedAtMs = 1, remoteSizeBytes = pending.sizeBytes
        )))
    }

    private fun clip() = RecordingClipEntity(
        id = "clip", cameraId = "camera01", relativePath = "camera01/test.mp4",
        startedAtMs = 1, durationMs = 60_000, sizeBytes = 100, modifiedMs = 1
    )
}
