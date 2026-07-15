package com.banupham.appviewcamera.viewer

import com.banupham.appviewcamera.viewer.settings.PairingUriParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingUriParserTest {
    @Test
    fun parsesGatewayPairingUri() {
        val config = PairingUriParser.parse(
            "appviewcamera://pair?gateway_id=gateway-home&host=192.168.1.20&api_port=8080&rtsp_port=8554&token=a_B-c%2E1"
        ).getOrThrow()

        assertEquals("gateway-home", config.id)
        assertEquals("192.168.1.20", config.host)
        assertEquals(8080, config.apiPort)
        assertEquals(8554, config.rtspPort)
        assertEquals("a_B-c.1", config.apiToken)
    }

    @Test
    fun rejectsForeignSchemeAndMissingSecret() {
        assertTrue(PairingUriParser.parse("https://pair?host=192.168.1.20&api_port=8080&rtsp_port=8554&token=x").isFailure)
        assertTrue(PairingUriParser.parse("appviewcamera://pair?host=192.168.1.20&api_port=8080&rtsp_port=8554").isFailure)
    }
}
