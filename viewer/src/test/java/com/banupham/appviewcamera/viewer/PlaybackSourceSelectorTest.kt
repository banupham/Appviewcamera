package com.banupham.appviewcamera.viewer

import com.banupham.appviewcamera.viewer.api.PlaybackItem
import com.banupham.appviewcamera.viewer.playback.PlaybackSourceSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSourceSelectorTest {
    @Test
    fun drivePlaysWhileYoutubeIsStillProcessing() {
        assertEquals("DRIVE_READY", PlaybackSourceSelector.select(item(drive = true)))
    }

    @Test
    fun youtubeIsFallbackWhenPrimarySourceFails() {
        assertEquals(
            "YOUTUBE_READY",
            PlaybackSourceSelector.select(item(drive = true, youtube = true), primaryFailed = true)
        )
    }

    @Test
    fun itemWithoutReadySourceCannotBePlayed() {
        assertNull(PlaybackSourceSelector.select(item(status = "PROCESSING")))
    }

    private fun item(
        drive: Boolean = false,
        youtube: Boolean = false,
        status: String = "READY"
    ) = PlaybackItem(
        id = "clip01",
        cameraId = "camera01",
        startTime = 1_000,
        endTime = 61_000,
        duration = 60_000,
        sizeBytes = 1,
        motion = false,
        protected = false,
        localAvailable = false,
        driveAvailable = drive,
        youtubeAvailable = youtube,
        youtubeVideoId = if (youtube) "private-video" else null,
        youtubeStartOffsetSeconds = 125,
        status = status,
        preferredSource = null,
        lastError = null
    )
}
