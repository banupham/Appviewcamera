package com.banupham.appviewcamera.viewer.settings

import android.content.Context
import com.banupham.appviewcamera.viewer.security.CredentialCipher

class GatewayConfigStore(
    context: Context,
    private val cipher: CredentialCipher
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): GatewayConfig {
        val encryptedToken = preferences.getString(KEY_TOKEN, "").orEmpty()
        val token = runCatching { cipher.decrypt(encryptedToken) }.getOrElse { "" }
        return GatewayConfig(
            host = preferences.getString(KEY_HOST, "").orEmpty(),
            apiPort = preferences.getInt(KEY_API_PORT, 8080),
            rtspPort = preferences.getInt(KEY_RTSP_PORT, 8554),
            apiToken = token
        )
    }

    fun save(config: GatewayConfig) {
        val valid = config.validate().getOrThrow()
        preferences.edit()
            .putString(KEY_HOST, valid.host)
            .putInt(KEY_API_PORT, valid.apiPort)
            .putInt(KEY_RTSP_PORT, valid.rtspPort)
            .putString(KEY_TOKEN, cipher.encrypt(valid.apiToken))
            .apply()
    }

    private companion object {
        const val PREFERENCES = "gateway_connection"
        const val KEY_HOST = "host"
        const val KEY_API_PORT = "api_port"
        const val KEY_RTSP_PORT = "rtsp_port"
        const val KEY_TOKEN = "api_token_encrypted"
    }
}
