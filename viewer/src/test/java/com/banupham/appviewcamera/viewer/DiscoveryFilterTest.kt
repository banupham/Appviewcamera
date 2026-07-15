package com.banupham.appviewcamera.viewer

import com.banupham.appviewcamera.viewer.api.CameraSummary
import com.banupham.appviewcamera.viewer.api.DiscoveryCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryFilterTest {
    @Test
    fun `web ports and configured cameras are hidden`() {
        val candidates = listOf(
            DiscoveryCandidate("192.168.1.7", 80, "tcp_scan"),
            DiscoveryCandidate("192.168.1.7", 554, "tcp_scan"),
            DiscoveryCandidate("192.168.1.8", 554, "tcp_scan")
        )
        val cameras = listOf(camera("192.168.1.7", 554))

        assertEquals(
            listOf(DiscoveryCandidate("192.168.1.8", 554, "tcp_scan")),
            availableDiscoveryCandidates(candidates, cameras)
        )
    }

    private fun camera(host: String, port: Int) = CameraSummary(
        id = "camera01", name = "Camera 01", host = host, port = port,
        username = "admin", mainPath = "live", subPath = "", relayPath = "camera01",
        enabled = true, recordingEnabled = true, motionEnabled = false
    )
}
