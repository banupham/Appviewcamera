package com.banupham.appviewcamera.gateway.rtsp

import org.junit.Assert.assertTrue
import org.junit.Test

class RtspFailureMessageTest {
    @Test
    fun classifiesAuthenticationFailureWithoutLeakingCredentials() {
        val error = Exception(
            "401 Unauthorized for rtsp://admin:secret@192.168.1.20/live"
        )
        val message = RtspFailureMessage.from(error, "TCP")

        assertTrue(message.contains("Sai tài khoản/mật khẩu"))
        assertTrue(message.contains("rtsp://***@"))
        assertTrue(!message.contains("secret"))
    }

    @Test
    fun classifiesNetworkFailure() {
        val message = RtspFailureMessage.from(Exception("Connection refused"), "AUTO/UDP")
        assertTrue(message.contains("Không kết nối được IP/cổng"))
    }
}
