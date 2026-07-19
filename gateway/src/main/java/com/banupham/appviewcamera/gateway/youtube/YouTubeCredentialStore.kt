package com.banupham.appviewcamera.gateway.youtube

import android.content.Context
import com.banupham.appviewcamera.gateway.security.CredentialCipher
import org.json.JSONArray
import org.json.JSONObject

data class YouTubeConfig(
    val clientId: String,
    val clientSecret: String,
    val targetDurationMinutes: Int
)

data class YouTubeAccount(
    val id: String,
    val displayName: String,
    val active: Boolean,
    val status: String = "ONLINE",
    val lastError: String? = null
)

class YouTubeCredentialStore(context: Context, private val cipher: CredentialCipher) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun configure(clientId: String, clientSecret: String, targetDurationMinutes: Int): YouTubeConfig {
        require(clientId.endsWith(".apps.googleusercontent.com")) { "YouTube OAuth client ID không hợp lệ" }
        require(clientSecret.isNotBlank()) { "YouTube OAuth client secret không hợp lệ" }
        require(targetDurationMinutes in setOf(60, 90, 120)) { "YouTube batch phải là 60, 90 hoặc 120 phút" }
        val config = YouTubeConfig(clientId.trim(), clientSecret.trim(), targetDurationMinutes)
        preferences.edit().putString(CONFIG, cipher.encrypt(JSONObject()
            .put("client_id", config.clientId)
            .put("client_secret", config.clientSecret)
            .put("target_duration_minutes", config.targetDurationMinutes)
            .toString())).apply()
        return config
    }

    fun config(): YouTubeConfig? = preferences.getString(CONFIG, null)?.let(cipher::decrypt)?.let(::JSONObject)?.let {
        YouTubeConfig(it.getString("client_id"), it.getString("client_secret"), it.optInt("target_duration_minutes", 60))
    }

    fun accounts(): List<YouTubeAccount> {
        val array = JSONArray(preferences.getString(ACCOUNTS, "[]"))
        return buildList {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                add(YouTubeAccount(
                    id = item.getString("id"),
                    displayName = item.optString("display_name").ifBlank { item.getString("id") },
                    active = item.optBoolean("active"),
                    status = item.optString("status", "ONLINE"),
                    lastError = item.optString("last_error").takeIf { value -> value.isNotBlank() }
                ))
            }
        }
    }

    fun saveAccount(id: String, displayName: String, token: JSONObject): YouTubeAccount {
        require(ID.matches(id)) { "ID YouTube không hợp lệ" }
        require(token.optString("access_token").isNotBlank()) { "YouTube token thiếu access token" }
        require(token.optString("refresh_token").isNotBlank()) { "Google không trả refresh token YouTube" }
        val previous = accounts()
        val active = previous.isEmpty() || previous.any { it.id == id && it.active }
        val updated = previous.filterNot { it.id == id }.map { it.copy(active = if (active) false else it.active) } +
            YouTubeAccount(id, displayName.ifBlank { id }, active)
        writeAccounts(updated)
        preferences.edit().putString(tokenKey(id), cipher.encrypt(token.toString())).apply()
        return updated.single { it.id == id }
    }

    fun token(id: String): JSONObject? = preferences.getString(tokenKey(id), null)?.let(cipher::decrypt)?.let(::JSONObject)

    fun updateToken(id: String, token: JSONObject) {
        require(accounts().any { it.id == id }) { "Không tìm thấy tài khoản YouTube" }
        preferences.edit().putString(tokenKey(id), cipher.encrypt(token.toString())).apply()
    }

    fun delete(id: String): Boolean {
        val existing = accounts()
        val remaining = existing.filterNot { it.id == id }.toMutableList()
        if (remaining.isNotEmpty() && remaining.none { it.active }) remaining[0] = remaining[0].copy(active = true)
        if (remaining.size == existing.size) return false
        writeAccounts(remaining)
        preferences.edit().remove(tokenKey(id)).apply()
        return true
    }

    private fun writeAccounts(accounts: List<YouTubeAccount>) {
        preferences.edit().putString(ACCOUNTS, JSONArray().apply {
            accounts.forEach { account -> put(JSONObject()
                .put("id", account.id)
                .put("display_name", account.displayName)
                .put("active", account.active)
                .put("status", account.status)
                .put("last_error", account.lastError ?: JSONObject.NULL))
            }
        }.toString()).apply()
    }

    private fun tokenKey(id: String) = "token_$id"

    private companion object {
        const val PREFERENCES = "gateway_youtube_credentials"
        const val CONFIG = "youtube_config"
        const val ACCOUNTS = "youtube_accounts"
        val ID = Regex("[a-z0-9_-]{1,32}")
    }
}
