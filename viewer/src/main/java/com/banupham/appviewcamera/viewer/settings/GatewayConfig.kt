package com.banupham.appviewcamera.viewer.settings

data class GatewayConfig(
    val host: String = "",
    val apiPort: Int = 8080,
    val rtspPort: Int = 8554,
    val apiToken: String = ""
) {
    fun validate(): Result<GatewayConfig> = runCatching {
        val cleanHost = host.trim()
        require(cleanHost.isNotEmpty()) { "Cần nhập IP hoặc hostname Gateway" }
        require(!cleanHost.contains("://") && !cleanHost.contains('/') && !cleanHost.contains('@')) {
            "Chỉ nhập IP/hostname, không nhập http:// hoặc đường dẫn"
        }
        require(cleanHost.none(Char::isWhitespace)) { "IP/hostname không được chứa khoảng trắng" }
        require(apiPort in 1..65535) { "API port phải nằm trong khoảng 1..65535" }
        require(rtspPort in 1..65535) { "RTSP port phải nằm trong khoảng 1..65535" }
        require(apiToken.isNotBlank()) { "Cần nhập API token của Gateway" }
        copy(host = cleanHost, apiToken = apiToken.trim())
    }

    val apiBaseUrl: String get() = "http://$host:$apiPort"

    fun relayUrl(relayPath: String): String {
        require(relayPath.isNotBlank()) { "Relay path không hợp lệ" }
        return "rtsp://$host:$rtspPort/${relayPath.trim('/')}"
    }

    fun recordingUrl(clipId: String): String = "$apiBaseUrl/api/recordings/$clipId/content"

    fun playbackStreamUrl(itemId: String, source: String = "auto"): String =
        "$apiBaseUrl/api/playback/items/$itemId/stream?source=$source"
}
