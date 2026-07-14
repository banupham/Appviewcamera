package com.banupham.appviewcamera.gateway.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspUrlFactoryTest {
    @Test
    fun validatesRtspSchemeAndHost() {
        assertTrue(RtspUrlFactory.isValid("rtsp://camera.local:554/live/main"))
        assertFalse(RtspUrlFactory.isValid("http://camera.local/live"))
        assertFalse(RtspUrlFactory.isValid("rtsp:///missing-host"))
    }

    @Test
    fun stripsCredentialsBeforeStorage() {
        assertEquals(
            "rtsp://camera.local:554/live/main?profile=1",
            RtspUrlFactory.withoutCredentials("rtsp://admin:secret@camera.local:554/live/main?profile=1")
        )
    }

    @Test
    fun insertsPercentEncodedCredentialsOnlyForConnection() {
        assertEquals(
            "rtsp://user%40home:p%40ss%20word@camera.local/live",
            RtspUrlFactory.withCredentials("rtsp://camera.local/live", "user@home", "p@ss word")
        )
    }
}
