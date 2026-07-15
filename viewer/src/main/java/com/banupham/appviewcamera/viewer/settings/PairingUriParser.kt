package com.banupham.appviewcamera.viewer.settings

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object PairingUriParser {
    fun parse(value: String): Result<GatewayConfig> = runCatching {
        val uri = URI(value.trim())
        require(uri.scheme.equals("appviewcamera", ignoreCase = true) && uri.host.equals("pair", ignoreCase = true)) {
            "Chuỗi ghép nối không đúng định dạng appviewcamera://pair"
        }
        val values = uri.rawQuery.orEmpty()
            .split('&')
            .filter { it.isNotBlank() }
            .associate { entry ->
                val parts = entry.split('=', limit = 2)
                decode(parts[0]) to decode(parts.getOrElse(1) { "" })
            }
        GatewayConfig(
            host = values["host"].orEmpty(),
            apiPort = values["api_port"]?.toIntOrNull() ?: -1,
            rtspPort = values["rtsp_port"]?.toIntOrNull() ?: -1,
            apiToken = values["token"].orEmpty(),
            id = values["gateway_id"].orEmpty(),
            name = values["gateway_name"].orEmpty()
        ).validate().getOrThrow()
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
