package com.banupham.appviewcamera.gateway.rtsp

object CredentialRedactor {
    private val userInfo = Regex("(?i)(rtsp://)[^/@\\s]+@")

    fun redact(message: String?): String = message
        ?.replace(userInfo, "\$1***@")
        ?.take(500)
        ?.ifBlank { "Lỗi RTSP không xác định" }
        ?: "Lỗi RTSP không xác định"
}
