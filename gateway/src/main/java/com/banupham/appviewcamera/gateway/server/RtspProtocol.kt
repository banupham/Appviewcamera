package com.banupham.appviewcamera.gateway.server

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

data class RtspPacket(
    val startLine: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray = byteArrayOf()
) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, true) }?.second

    fun withTarget(target: String): RtspPacket {
        val parts = startLine.split(' ', limit = 3)
        require(parts.size == 3) { "RTSP request line không hợp lệ" }
        return copy(startLine = "${parts[0]} $target ${parts[2]}")
    }

    fun replacingHeader(name: String, value: String): RtspPacket = copy(
        headers = headers.filterNot { it.first.equals(name, true) } + (name to value)
    )

    fun withoutHeader(name: String): RtspPacket = copy(headers = headers.filterNot { it.first.equals(name, true) })

    fun writeTo(output: OutputStream) {
        val actualHeaders = headers.filterNot { it.first.equals("Content-Length", true) }.toMutableList()
        if (body.isNotEmpty()) actualHeaders += "Content-Length" to body.size.toString()
        val head = buildString {
            append(startLine).append("\r\n")
            actualHeaders.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        output.write(head.toByteArray(StandardCharsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    companion object {
        fun readFrom(input: InputStream): RtspPacket? {
            val head = readHead(input) ?: return null
            val lines = head.split("\r\n")
            val startLine = lines.firstOrNull().orEmpty()
            require(startLine.isNotBlank()) { "RTSP message rỗng" }
            val headers = lines.drop(1).filter(String::isNotBlank).map { line ->
                val separator = line.indexOf(':')
                require(separator > 0) { "RTSP header không hợp lệ" }
                line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }
            val length = headers.firstOrNull { it.first.equals("Content-Length", true) }
                ?.second?.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(body, offset, length - offset)
                if (count < 0) error("RTSP body bị ngắt")
                offset += count
            }
            return RtspPacket(startLine, headers, body)
        }

        private fun readHead(input: InputStream): String? {
            val output = ByteArrayOutputStream()
            var matched = 0
            while (output.size() < MAX_HEADER_BYTES) {
                val value = input.read()
                if (value < 0) return if (output.size() == 0) null else error("RTSP header bị ngắt")
                output.write(value)
                matched = when {
                    matched == 0 && value == '\r'.code -> 1
                    matched == 1 && value == '\n'.code -> 2
                    matched == 2 && value == '\r'.code -> 3
                    matched == 3 && value == '\n'.code -> return output.toString(StandardCharsets.ISO_8859_1.name()).dropLast(4)
                    value == '\r'.code -> 1
                    else -> 0
                }
            }
            error("RTSP header vượt quá $MAX_HEADER_BYTES bytes")
        }

        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_BODY_BYTES = 2 * 1024 * 1024
    }
}
