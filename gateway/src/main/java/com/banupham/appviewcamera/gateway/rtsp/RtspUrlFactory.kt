package com.banupham.appviewcamera.gateway.rtsp

import java.net.URI

object RtspUrlFactory {
    fun isValid(url: String): Boolean = runCatching {
        val uri = URI(url.trim())
        uri.scheme.equals("rtsp", ignoreCase = true) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    fun withoutCredentials(url: String): String {
        val uri = URI(url)
        return buildUrl(uri, null)
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

    private const val HEX = "0123456789ABCDEF"
}
