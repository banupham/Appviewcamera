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

    @Test
    fun extractsEmbeddedCredentialsBeforeRemovingThemFromStorageUrl() {
        assertEquals(
            RtspCredentials("admin@home", "p@ss+word"),
            RtspUrlFactory.credentials("rtsp://admin%40home:p%40ss%2Bword@192.168.1.20/live")
        )
    }

    @Test
    fun repairsRawAtSignInsidePastedPassword() {
        val normalized = RtspUrlFactory.normalize(
            "rtsp://admin:Vin@@192.168.1.20:554/Streaming/Channels/101",
            "192.168.1.20",
            554
        )

        assertEquals(
            "rtsp://admin:Vin%40@192.168.1.20:554/Streaming/Channels/101",
            normalized
        )
        assertEquals(RtspCredentials("admin", "Vin@"), RtspUrlFactory.credentials(normalized))
    }

    @Test
    fun normalizesFullUrlToExplicitIpAndPortWithoutLosingVendorQuery() {
        assertEquals(
            "rtsp://admin:secret@192.168.1.20:8554/cam/realmonitor?channel=1&subtype=0",
            RtspUrlFactory.normalize(
                "rtsp://admin:secret@old-host:554/cam/realmonitor?channel=1&subtype=0",
                "192.168.1.20",
                8554
            )
        )
    }

    @Test
    fun buildsUrlFromBarePathAndSupportsIpv6() {
        assertEquals(
            "rtsp://[fd00::20]:554/Streaming/Channels/101",
            RtspUrlFactory.normalize("/Streaming/Channels/101", "fd00::20", 554)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonRtspSchemeInsteadOfTreatingItAsPath() {
        RtspUrlFactory.normalize("http://camera/live", "camera", 554)
    }
}
