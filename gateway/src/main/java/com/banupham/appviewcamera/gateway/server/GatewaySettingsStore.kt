package com.banupham.appviewcamera.gateway.server

import android.content.Context
import android.net.Uri
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GatewaySettings(
    val gatewayId: String,
    val gatewayName: String,
    val apiPort: Int,
    val rtspPort: Int,
    val apiToken: String,
    val autoStart: Boolean,
    val pairingHost: String
) {
    fun pairingUri(host: String): String = Uri.Builder()
        .scheme("appviewcamera")
        .authority("pair")
        .appendQueryParameter("gateway_id", gatewayId)
        .appendQueryParameter("gateway_name", gatewayName)
        .appendQueryParameter("host", host)
        .appendQueryParameter("api_port", apiPort.toString())
        .appendQueryParameter("rtsp_port", rtspPort.toString())
        .appendQueryParameter("token", apiToken)
        .build()
        .toString()
}

class GatewaySettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val _settings = MutableStateFlow(load())
    val settings = _settings.asStateFlow()

    fun setAutoStart(enabled: Boolean) = update(_settings.value.copy(autoStart = enabled))

    fun setPairingHost(host: String) = update(_settings.value.copy(pairingHost = host))

    fun rotateToken(): GatewaySettings {
        val updated = _settings.value.copy(apiToken = newToken())
        update(updated)
        return updated
    }

    private fun load(): GatewaySettings {
        val id = preferences.getString(KEY_ID, null).orEmpty().ifBlank {
            "android-${UUID.randomUUID().toString().substring(0, 8)}"
        }
        val token = preferences.getString(KEY_TOKEN, null).orEmpty().ifBlank(::newToken)
        val result = GatewaySettings(
            gatewayId = id,
            gatewayName = preferences.getString(KEY_NAME, null).orEmpty().ifBlank { "Android Gateway" },
            apiPort = preferences.getInt(KEY_API_PORT, DEFAULT_API_PORT),
            rtspPort = preferences.getInt(KEY_RTSP_PORT, DEFAULT_RTSP_PORT),
            apiToken = token,
            autoStart = preferences.getBoolean(KEY_AUTO_START, false),
            pairingHost = preferences.getString(KEY_PAIRING_HOST, "").orEmpty()
        )
        persist(result)
        return result
    }

    private fun update(value: GatewaySettings) {
        persist(value)
        _settings.value = value
    }

    private fun persist(value: GatewaySettings) {
        preferences.edit()
            .putString(KEY_ID, value.gatewayId)
            .putString(KEY_NAME, value.gatewayName)
            .putInt(KEY_API_PORT, value.apiPort)
            .putInt(KEY_RTSP_PORT, value.rtspPort)
            .putString(KEY_TOKEN, value.apiToken)
            .putBoolean(KEY_AUTO_START, value.autoStart)
            .putString(KEY_PAIRING_HOST, value.pairingHost)
            .apply()
    }

    private fun newToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
    }

    private companion object {
        const val PREFERENCES = "gateway_server"
        const val KEY_ID = "gateway_id"
        const val KEY_NAME = "gateway_name"
        const val KEY_API_PORT = "api_port"
        const val KEY_RTSP_PORT = "rtsp_port"
        const val KEY_TOKEN = "api_token"
        const val KEY_AUTO_START = "auto_start"
        const val KEY_PAIRING_HOST = "pairing_host"
        const val DEFAULT_API_PORT = 8080
        const val DEFAULT_RTSP_PORT = 8554
    }
}

object LanAddressResolver {
    fun resolveAll(): List<String> = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            .mapNotNull { it.hostAddress }
            .distinct()
            .sortedWith(compareByDescending<String> { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") })
            .toList()
    }.getOrDefault(emptyList()).ifEmpty { listOf("127.0.0.1") }

    fun resolve(): String = resolveAll().first()
}
