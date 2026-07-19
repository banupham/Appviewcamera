package com.banupham.appviewcamera.gateway.rtsp

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class RtspCredentials(val username: String, val password: String)

object RtspUrlFactory {
    fun isValid(url: String): Boolean = runCatching {
        val uri = parseRtspUri(url.trim())
        uri.scheme.equals("rtsp", ignoreCase = true) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    fun withoutCredentials(url: String): String {
        val uri = parseRtspUri(url.trim())
        return buildUrl(uri, null)
    }

    fun credentials(url: String): RtspCredentials? {
        val rawUserInfo = runCatching { parseRtspUri(url.trim()).rawUserInfo }.getOrNull() ?: return null
        return runCatching {
            val separator = rawUserInfo.indexOf(':')
            val rawUsername = if (separator >= 0) rawUserInfo.substring(0, separator) else rawUserInfo
            val rawPassword = if (separator >= 0) rawUserInfo.substring(separator + 1) else ""
            RtspCredentials(decodeUserInfo(rawUsername), decodeUserInfo(rawPassword))
        }.getOrNull()
    }

    /**
     * Uses the explicit camera endpoint as the source of truth while preserving
     * vendor-specific path/query data. A bare path is accepted for easier setup.
     */
    fun normalize(urlOrPath: String, host: String, port: Int): String {
        val endpoint = host.trim()
        require(endpoint.isNotBlank()) { "RTSP URL thiếu hostname" }
        require(port in 1..65535) { "Cổng RTSP không hợp lệ" }
        val input = urlOrPath.trim()
        require(input.isNotBlank()) { "RTSP URL hoặc path không được để trống" }
        val displayHost = if (endpoint.removePrefix("[").removeSuffix("]").contains(':')) {
            "[${endpoint.removePrefix("[").removeSuffix("]")}]"
        } else endpoint
        if (!input.startsWith("rtsp://", ignoreCase = true)) {
            require(!SCHEME_PATTERN.containsMatchIn(input)) { "Chỉ hỗ trợ giao thức rtsp://" }
            val suffix = input.trimStart('/')
            val normalized = "rtsp://$displayHost:$port/$suffix"
            require(isValid(normalized)) { "RTSP path không hợp lệ" }
            return normalized
        }
        val uri = parseRtspUri(input)
        require(uri.scheme.equals("rtsp", ignoreCase = true)) { "Chỉ hỗ trợ giao thức rtsp://" }
        val credentials = uri.rawUserInfo?.let { "$it@" }.orEmpty()
        val path = uri.rawPath.orEmpty().ifBlank { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        val normalized = "rtsp://$credentials$displayHost:$port$path$query$fragment"
        require(isValid(normalized)) { "RTSP URL không hợp lệ" }
        return normalized
    }

    fun withCredentials(url: String, username: String, password: String): String {
        val uri = URI(url)
        val encodedUserInfo = when {
            username.isEmpty() -> null
            password.isEmpty() -> encodeUserInfo(username)
            else -> "${encodeUserInfo(username)}:${encodeUserInfo(password)}"
        }
        return buildUrl(uri, encodedUserInfo)
    }

    private fun buildUrl(uri: URI, encodedUserInfo: String?): String {
        val host = requireNotNull(uri.host) { "RTSP URL thiếu hostname" }
        val displayHost = if (host.contains(':')) "[$host]" else host
        val credentials = encodedUserInfo?.let { "$it@" }.orEmpty()
        val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "${uri.scheme}://$credentials$displayHost$port$path$query$fragment"
    }

    private fun encodeUserInfo(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val number = byte.toInt() and 0xff
            val character = number.toChar()
            val isAsciiUnreserved = number in 'a'.code..'z'.code ||
                number in 'A'.code..'Z'.code ||
                number in '0'.code..'9'.code ||
                character in "-._~"
            if (isAsciiUnreserved) {
                append(character)
            } else {
                append('%')
                append(HEX[number ushr 4])
                append(HEX[number and 0x0f])
            }
        }
    }

    private fun decodeUserInfo(value: String): String = URLDecoder.decode(
        value.replace("+", "%2B"),
        StandardCharsets.UTF_8.name()
    )

    /** Accepts a raw '@' inside pasted credentials by treating the final '@' as the host delimiter. */
    private fun parseRtspUri(value: String): URI {
        val direct = runCatching { URI(value) }.getOrNull()
        if (direct?.scheme.equals("rtsp", true) && !direct?.host.isNullOrBlank()) return requireNotNull(direct)
        require(value.startsWith("rtsp://", true)) { "Chỉ hỗ trợ giao thức rtsp://" }
        val authorityStart = value.indexOf("//") + 2
        val authorityEnd = sequenceOf(
            value.indexOf('/', authorityStart),
            value.indexOf('?', authorityStart),
            value.indexOf('#', authorityStart)
        ).filter { it >= 0 }.minOrNull() ?: value.length
        val authority = value.substring(authorityStart, authorityEnd)
        val hostDelimiter = authority.lastIndexOf('@')
        require(hostDelimiter > 0) { "RTSP URL thiếu hostname" }
        val rawUserInfo = authority.substring(0, hostDelimiter)
        val hostAndPort = authority.substring(hostDelimiter + 1)
        require(hostAndPort.isNotBlank()) { "RTSP URL thiếu hostname" }
        val separator = rawUserInfo.indexOf(':')
        val username = decodeUserInfo(if (separator >= 0) rawUserInfo.substring(0, separator) else rawUserInfo)
        val password = decodeUserInfo(if (separator >= 0) rawUserInfo.substring(separator + 1) else "")
        val encodedUserInfo = if (separator >= 0) {
            "${encodeUserInfo(username)}:${encodeUserInfo(password)}"
        } else encodeUserInfo(username)
        val repaired = value.substring(0, authorityStart) + encodedUserInfo + "@" +
            hostAndPort + value.substring(authorityEnd)
        return URI(repaired).also { require(!it.host.isNullOrBlank()) { "RTSP URL thiếu hostname" } }
    }

    private const val HEX = "0123456789ABCDEF"
    private val SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
}
