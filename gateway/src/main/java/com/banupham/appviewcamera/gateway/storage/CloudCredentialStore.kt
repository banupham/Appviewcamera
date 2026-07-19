package com.banupham.appviewcamera.gateway.storage

import android.content.Context
import com.banupham.appviewcamera.gateway.security.CredentialCipher
import org.json.JSONArray
import org.json.JSONObject

data class DriveAccount(
    val id: String,
    val displayName: String,
    val active: Boolean,
    val status: String = "NOT_CHECKED",
    val lastError: String? = null
)

data class GoogleOAuthClient(
    val clientId: String,
    val clientSecret: String,
    val authUri: String,
    val tokenUri: String,
    val redirectUri: String
)

class CloudCredentialStore(
    context: Context,
    private val cipher: CredentialCipher
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun oauthClient(): GoogleOAuthClient? = encryptedJson(OAUTH_CLIENT)?.let(::parseOAuthClient)

    fun saveOAuthClient(client: GoogleOAuthClient) {
        require(client.clientId.endsWith(".apps.googleusercontent.com")) { "Google OAuth client ID không hợp lệ" }
        require(client.clientSecret.isNotBlank()) { "Google OAuth client secret không hợp lệ" }
        require(client.authUri.startsWith("https://") && client.tokenUri.startsWith("https://")) {
            "OAuth endpoint phải dùng HTTPS"
        }
        require(client.redirectUri == "http://localhost:53682/") {
            "Redirect URI phải là http://localhost:53682/"
        }
        preferences.edit().putString(OAUTH_CLIENT, cipher.encrypt(JSONObject().apply {
            put("client_id", client.clientId)
            put("client_secret", client.clientSecret)
            put("auth_uri", client.authUri)
            put("token_uri", client.tokenUri)
            put("redirect_uri", client.redirectUri)
        }.toString())).apply()
    }

    fun accounts(): List<DriveAccount> {
        val raw = JSONArray(preferences.getString(ACCOUNTS, "[]"))
        return buildList {
            repeat(raw.length()) { index ->
                val item = raw.getJSONObject(index)
                add(DriveAccount(
                    id = item.getString("id"),
                    displayName = item.optString("display_name").ifBlank { item.getString("id") },
                    active = item.optBoolean("active"),
                    status = item.optString("status", "NOT_CHECKED"),
                    lastError = item.optString("last_error").takeIf { it.isNotBlank() }
                ))
            }
        }
    }

    fun saveAccount(id: String, displayName: String, oauthToken: String): DriveAccount {
        require(ID.matches(id)) { "ID Drive không hợp lệ" }
        require(oauthToken.length in 2..16_384) { "OAuth token không hợp lệ" }
        JSONObject(oauthToken)
        val previous = accounts()
        val active = previous.isEmpty() || previous.any { it.id == id && it.active }
        val updated = (previous.filterNot { it.id == id }.map { it.copy(active = if (active) false else it.active) } +
            DriveAccount(id, displayName.ifBlank { id }, active)).toMutableList()
        writeAccounts(updated)
        preferences.edit().putString(tokenKey(id), cipher.encrypt(oauthToken)).apply()
        return updated.single { it.id == id }
    }

    fun token(id: String): String? = preferences.getString(tokenKey(id), null)?.let(cipher::decrypt)

    fun updateToken(id: String, oauthToken: String) {
        require(accounts().any { it.id == id }) { "Không tìm thấy tài khoản Google Drive" }
        require(oauthToken.length in 2..16_384) { "OAuth token không hợp lệ" }
        JSONObject(oauthToken)
        preferences.edit().putString(tokenKey(id), cipher.encrypt(oauthToken)).apply()
    }

    fun activate(id: String): DriveAccount {
        val updated = accounts().map { it.copy(active = it.id == id) }
        require(updated.any { it.active }) { "Không tìm thấy tài khoản Google Drive" }
        writeAccounts(updated)
        return updated.single { it.active }
    }

    fun delete(id: String): Boolean {
        val remaining = accounts().filterNot { it.id == id }.toMutableList()
        if (remaining.isNotEmpty() && remaining.none { it.active }) remaining[0] = remaining[0].copy(active = true)
        val existed = remaining.size != accounts().size
        if (existed) {
            writeAccounts(remaining)
            preferences.edit().remove(tokenKey(id)).apply()
        }
        return existed
    }

    private fun writeAccounts(accounts: List<DriveAccount>) {
        preferences.edit().putString(ACCOUNTS, JSONArray().apply {
            accounts.forEach { account -> put(JSONObject().apply {
                put("id", account.id)
                put("display_name", account.displayName)
                put("active", account.active)
                put("status", account.status)
                put("last_error", account.lastError ?: JSONObject.NULL)
            }) }
        }.toString()).apply()
    }

    private fun encryptedJson(key: String): JSONObject? = preferences.getString(key, null)
        ?.let(cipher::decrypt)
        ?.let(::JSONObject)

    private fun parseOAuthClient(value: JSONObject) = GoogleOAuthClient(
        clientId = value.getString("client_id"),
        clientSecret = value.getString("client_secret"),
        authUri = value.getString("auth_uri"),
        tokenUri = value.getString("token_uri"),
        redirectUri = value.getString("redirect_uri")
    )

    private fun tokenKey(id: String) = "token_$id"

    private companion object {
        const val PREFERENCES = "gateway_cloud_credentials"
        const val OAUTH_CLIENT = "oauth_client"
        const val ACCOUNTS = "drive_accounts"
        val ID = Regex("[a-z0-9_-]{1,32}")
    }
}
