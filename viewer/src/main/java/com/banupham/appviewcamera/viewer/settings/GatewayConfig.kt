package com.banupham.appviewcamera.viewer.settings

import java.security.MessageDigest

data class GatewayConfig(
    val host: String = "",
    val apiPort: Int = 8080,
    val rtspPort: Int = 8554,
    val apiToken: String = "",
    val id: String = "",
    val name: String = "",
    val lastSeen: Long? = null,
    val status: String = "UNKNOWN",
    val cameraCount: Int = 0,
    val isDefault: Boolean = false
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
        val cleanId = id.trim().ifBlank { GatewayIdentity.fromEndpoint(cleanHost, apiPort) }
        require(cleanId.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Gateway ID không hợp lệ" }
        copy(
            id = cleanId,
            name = name.trim().ifBlank { "Gateway $cleanHost" },
            host = cleanHost,
            apiToken = apiToken.trim(),
            status = status.ifBlank { "UNKNOWN" },
            cameraCount = cameraCount.coerceAtLeast(0)
        )
    }

    val apiBaseUrl: String get() = "http://${hostForUrl()}:$apiPort"

    fun relayUrl(relayPath: String): String {
        require(relayPath.isNotBlank()) { "Relay path không hợp lệ" }
        return "rtsp://${hostForUrl()}:$rtspPort/${relayPath.trim('/')}"
    }

    fun recordingUrl(clipId: String): String = "$apiBaseUrl/api/recordings/$clipId/content"

    fun playbackStreamUrl(itemId: String, source: String = "auto"): String =
        "$apiBaseUrl/api/playback/items/$itemId/stream?source=$source"

    private fun hostForUrl(): String {
        val unwrapped = host.removePrefix("[").removeSuffix("]")
        return if (unwrapped.contains(':')) "[$unwrapped]" else unwrapped
    }
}

data class GatewayCollection(
    val gateways: List<GatewayConfig> = emptyList(),
    val currentGatewayId: String? = null
) {
    val current: GatewayConfig?
        get() = gateways.firstOrNull { it.id == currentGatewayId }
            ?: gateways.firstOrNull { it.isDefault }
            ?: gateways.firstOrNull()

    fun upsert(config: GatewayConfig, select: Boolean = true): GatewayCollection {
        val valid = config.validate().getOrThrow()
        val existing = gateways.firstOrNull { it.id == valid.id }
        val makeDefault = valid.isDefault || (existing?.isDefault == true) || gateways.isEmpty()
        val saved = valid.copy(isDefault = makeDefault)
        val updated = gateways.filterNot { it.id == saved.id }.map {
            if (makeDefault) it.copy(isDefault = false) else it
        } + saved
        return copy(
            gateways = updated,
            currentGatewayId = if (select) saved.id else currentGatewayId ?: updated.firstOrNull()?.id
        )
    }

    fun select(gatewayId: String): GatewayCollection {
        require(gateways.any { it.id == gatewayId }) { "Không tìm thấy Gateway" }
        return copy(currentGatewayId = gatewayId)
    }

    fun remove(gatewayId: String): GatewayCollection {
        val removed = gateways.firstOrNull { it.id == gatewayId } ?: return this
        var remaining = gateways.filterNot { it.id == gatewayId }
        if (removed.isDefault && remaining.isNotEmpty()) {
            remaining = remaining.mapIndexed { index, item -> item.copy(isDefault = index == 0) }
        }
        val nextCurrent = currentGatewayId?.takeIf { id -> remaining.any { it.id == id } }
            ?: remaining.firstOrNull { it.isDefault }?.id
            ?: remaining.firstOrNull()?.id
        return GatewayCollection(remaining, nextCurrent)
    }

    fun updateStatus(
        gatewayId: String,
        status: String,
        lastSeen: Long? = null,
        cameraCount: Int? = null
    ): GatewayCollection = copy(
        gateways = gateways.map { gateway ->
            if (gateway.id != gatewayId) gateway else gateway.copy(
                status = status,
                lastSeen = lastSeen ?: gateway.lastSeen,
                cameraCount = cameraCount ?: gateway.cameraCount
            )
        }
    )
}

object GatewayIdentity {
    fun fromEndpoint(host: String, apiPort: Int): String {
        val input = "${host.trim().lowercase()}:$apiPort".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return "legacy-" + digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
