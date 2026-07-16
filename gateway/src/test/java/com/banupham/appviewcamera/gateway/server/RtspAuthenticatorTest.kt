package com.banupham.appviewcamera.gateway.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspAuthenticatorTest {
    @Test
    fun createsBasicAuthorizationAfterChallenge() {
        val authenticator = RtspAuthenticator("admin", "secret")

        assertTrue(authenticator.acceptChallenge("Basic realm=\"camera\""))
        assertEquals("Basic YWRtaW46c2VjcmV0", authenticator.authorization("DESCRIBE", "rtsp://camera/live"))
    }

    @Test
    fun createsRfcCompatibleDigestWithoutQop() {
        val authenticator = RtspAuthenticator("Mufasa", "Circle Of Life")

        assertTrue(
            authenticator.acceptChallenge(
                "Digest realm=\"testrealm@host.com\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\""
            )
        )
        val value = authenticator.authorization("DESCRIBE", "rtsp://camera/live").orEmpty()

        assertTrue(value.startsWith("Digest "))
        assertTrue(value.contains("uri=\"rtsp://camera/live\""))
        assertTrue(value.contains("response=\"a1bae0e2a331ee605a8329cf11825700\""))
    }

    @Test
    fun supportsQuotedQopLists() {
        val authenticator = RtspAuthenticator("admin", "secret")

        assertTrue(authenticator.acceptChallenge("Digest realm=\"cam\", nonce=\"abc\", qop=\"auth,auth-int\""))
        val value = authenticator.authorization("SETUP", "rtsp://camera/live/track1").orEmpty()

        assertTrue(value.contains("qop=auth"))
        assertTrue(value.contains("nc=00000001"))
        assertTrue(value.contains("cnonce=\""))
    }
}
