package com.banupham.appviewcamera.viewer.settings

import android.content.Context
import com.banupham.appviewcamera.viewer.security.CredentialCipher
import org.json.JSONArray
import org.json.JSONObject

class GatewayConfigStore(
    context: Context,
    private val cipher: CredentialCipher
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun loadCollection(): GatewayCollection {
        val stored = preferences.getString(KEY_GATEWAYS, null)
        if (stored != null) return parseCollection(stored)
        val migrated = migrateLegacy()
        if (migrated.gateways.isNotEmpty()) persist(migrated)
        return migrated
    }

    fun load(): GatewayConfig = loadCollection().current ?: GatewayConfig()

    @Synchronized
    fun save(config: GatewayConfig): GatewayCollection {
        val updated = loadCollection().upsert(config, select = true)
        persist(updated)
        return updated
    }

    @Synchronized
    fun select(gatewayId: String): GatewayCollection {
        val updated = loadCollection().select(gatewayId)
        persist(updated)
        return updated
    }

    @Synchronized
    fun delete(gatewayId: String): GatewayCollection {
        val updated = loadCollection().remove(gatewayId)
        persist(updated)
        return updated
    }

    @Synchronized
    fun rename(gatewayId: String, name: String): GatewayCollection = update(gatewayId) {
        it.copy(name = name.trim().ifBlank { it.name })
    }

    @Synchronized
    fun updateStatus(
        gatewayId: String,
        status: String,
        lastSeen: Long? = null,
        cameraCount: Int? = null
    ): GatewayCollection {
        val changed = loadCollection().updateStatus(gatewayId, status, lastSeen, cameraCount)
        persist(changed)
        return changed
    }

    private fun update(gatewayId: String, transform: (GatewayConfig) -> GatewayConfig): GatewayCollection {
        val current = loadCollection()
        val target = current.gateways.firstOrNull { it.id == gatewayId } ?: return current
        val changed = current.copy(
            gateways = current.gateways.map { if (it.id == gatewayId) transform(target) else it }
        )
        persist(changed)
        return changed
    }

    private fun parseCollection(payload: String): GatewayCollection {
        val root = JSONObject(payload)
        val array = root.optJSONArray("gateways") ?: JSONArray()
        val gateways = buildList {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val encryptedToken = item.optString("encrypted_api_token")
                val token = cipher.decrypt(encryptedToken)
                add(
                    GatewayConfig(
                        host = item.getString("host"),
                        apiPort = item.optInt("api_port", 8080),
                        rtspPort = item.optInt("rtsp_port", 8554),
                        apiToken = token,
                        id = item.getString("id"),
                        name = item.optString("name"),
                        lastSeen = item.optionalLong("last_seen"),
                        status = item.optString("status", "UNKNOWN"),
                        cameraCount = item.optInt("camera_count", 0),
                        isDefault = item.optBoolean("is_default", false)
                    ).validate().getOrThrow()
                )
            }
        }
        val requestedCurrent = root.optString("current_gateway_id").takeIf(String::isNotBlank)
        return GatewayCollection(gateways, requestedCurrent).let { collection ->
            collection.copy(currentGatewayId = collection.current?.id)
        }
    }

    private fun migrateLegacy(): GatewayCollection {
        val host = preferences.getString(KEY_HOST, "").orEmpty()
        if (host.isBlank()) return GatewayCollection()
        val encryptedToken = preferences.getString(KEY_TOKEN, "").orEmpty()
        val token = cipher.decrypt(encryptedToken)
        val config = GatewayConfig(
            host = host,
            apiPort = preferences.getInt(KEY_API_PORT, 8080),
            rtspPort = preferences.getInt(KEY_RTSP_PORT, 8554),
            apiToken = token,
            name = "Gateway $host",
            isDefault = true
        ).validate().getOrThrow()
        return GatewayCollection(listOf(config), config.id)
    }

    private fun persist(collection: GatewayCollection) {
        val array = JSONArray()
        collection.gateways.forEach { gateway ->
            array.put(
                JSONObject().apply {
                    put("id", gateway.id)
                    put("name", gateway.name)
                    put("host", gateway.host)
                    put("api_port", gateway.apiPort)
                    put("rtsp_port", gateway.rtspPort)
                    put("encrypted_api_token", cipher.encrypt(gateway.apiToken))
                    put("last_seen", gateway.lastSeen ?: JSONObject.NULL)
                    put("status", gateway.status)
                    put("camera_count", gateway.cameraCount)
                    put("is_default", gateway.isDefault)
                }
            )
        }
        val root = JSONObject().apply {
            put("gateways", array)
            put("current_gateway_id", collection.current?.id ?: JSONObject.NULL)
        }
        preferences.edit().putString(KEY_GATEWAYS, root.toString()).apply()
    }

    private fun JSONObject.optionalLong(name: String): Long? =
        if (has(name) && !isNull(name)) getLong(name) else null

    private companion object {
        const val PREFERENCES = "gateway_connection"
        const val KEY_GATEWAYS = "gateways_json_v2"
        const val KEY_HOST = "host"
        const val KEY_API_PORT = "api_port"
        const val KEY_RTSP_PORT = "rtsp_port"
        const val KEY_TOKEN = "api_token_encrypted"
    }
}
