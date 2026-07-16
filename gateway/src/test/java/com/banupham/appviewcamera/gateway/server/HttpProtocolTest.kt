package com.banupham.appviewcamera.gateway.server

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpProtocolTest {
    @Test
    fun parsesAuthenticatedJsonRequest() {
        val body = "{\"id\":\"camera01\"}".toByteArray()
        val bytes = buildString {
            append("PUT /api/cameras/camera01 HTTP/1.1\r\n")
            append("Authorization: Bearer token\r\n")
            append("Content-Length: ${body.size}\r\n\r\n")
        }.toByteArray() + body

        val request = HttpRequest.readFrom(ByteArrayInputStream(bytes))

        requireNotNull(request)
        assertEquals("PUT", request.method)
        assertEquals("/api/cameras/camera01", request.target)
        assertEquals("Bearer token", request.headers["authorization"])
        assertArrayEquals(body, request.body)
    }

    @Test
    fun parsesOpenEndedAndSuffixByteRanges() {
        assertEquals(20L..99L, ByteRange.parse("bytes=20-", 100))
        assertEquals(90L..99L, ByteRange.parse("bytes=-10", 100))
        assertEquals(20L..40L, ByteRange.parse("bytes=20-40", 100))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRangeOutsideFile() {
        ByteRange.parse("bytes=100-120", 100)
    }
}
