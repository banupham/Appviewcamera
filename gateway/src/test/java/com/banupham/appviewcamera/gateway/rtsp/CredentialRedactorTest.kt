package com.banupham.appviewcamera.gateway.rtsp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialRedactorTest {
    @Test
    fun removesUserInfoFromErrors() {
        val result = CredentialRedactor.redact("Cannot open rtsp://admin:very-secret@camera.local/live")
        assertFalse(result.contains("admin"))
        assertFalse(result.contains("very-secret"))
        assertTrue(result.contains("rtsp://***@camera.local/live"))
    }
}
