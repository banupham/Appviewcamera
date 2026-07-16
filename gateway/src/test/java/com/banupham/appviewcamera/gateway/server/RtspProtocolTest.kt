package com.banupham.appviewcamera.gateway.server

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtspProtocolTest {
    @Test
    fun roundTripsRequestWithBody() {
        val original = RtspPacket(
            startLine = "ANNOUNCE rtsp://gateway:8554/camera01 RTSP/1.0",
            headers = listOf("CSeq" to "7", "Content-Type" to "application/sdp"),
            body = "v=0\r\n".toByteArray()
        )
        val output = ByteArrayOutputStream()

        original.writeTo(output)
        val parsed = RtspPacket.readFrom(ByteArrayInputStream(output.toByteArray()))

        requireNotNull(parsed)
        assertEquals(original.startLine, parsed.startLine)
        assertEquals("7", parsed.header("cseq"))
        assertArrayEquals(original.body, parsed.body)
    }

    @Test
    fun returnsNullAtCleanEndOfStream() {
        assertNull(RtspPacket.readFrom(ByteArrayInputStream(byteArrayOf())))
    }
}
