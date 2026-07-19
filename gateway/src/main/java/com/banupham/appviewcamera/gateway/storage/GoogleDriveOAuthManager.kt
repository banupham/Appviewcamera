package com.banupham.appviewcamera.gateway.storage

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class DriveOAuthSession(
    val sessionId: String,
    val remoteId: String,
    val displayName: String,
    val status: String,
    val authorizationUrl: String?,
    val error: String?
) {
    fun json(): JSONObject = JSONObject()
        .put("session_id", sessionId)
        .put("remote_id", remoteId)
        .put("display_name", displayName)
        .put("status", status)
        .put("authorization_url", authorizationUrl ?: JSONObject.NULL)
        .put("error", error ?: JSONObject.NULL)
}

class GoogleDriveOAuthManager(
    private val credentials: CloudCredentialStore,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val sessions = ConcurrentHashMap<String, PendingSession>()

    fun start(remoteId: String, displayName: String): DriveOAuthSession {
        require(ID.matches(remoteId)) { "ID Drive không hợp lệ" }
        val client = requireNotNull(credentials.oauthClient()) { "Gateway chưa cấu hình Google OAuth" }
        val sessionId = randomToken()
        val state = randomToken()
        val session = PendingSession(sessionId, remoteId, displayName.ifBlank { remoteId }, state)
        sessions[sessionId] = session
        val authorizationUrl = client.authUri + "?" + form(
            "client_id" to client.clientId,
            "redirect_uri" to client.redirectUri,
            "response_type" to "code",
            "scope" to DRIVE_SCOPE,
            "access_type" to "offline",
            "prompt" to "consent",
            "state" to state
        )
        return session.snapshot("WAITING_BROWSER", authorizationUrl, null)
    }

    fun status(sessionId: String): DriveOAuthSession =
        requireNotNull(sessions[sessionId]) { "Không tìm thấy phiên OAuth" }.current

    suspend fun callback(sessionId: String, callbackPath: String): JSONObject {
        val session = requireNotNull(sessions[sessionId]) { "Không tìm thấy phiên OAuth" }
        return runCatching {
            val query = query(callbackPath)
            require(query["state"] == session.state) { "OAuth state không hợp lệ" }
            query["error"]?.let { error("Google OAuth từ chối: $it") }
            val code = requireNotNull(query["code"]) { "OAuth callback thiếu authorization code" }
            val client = requireNotNull(credentials.oauthClient()) { "Gateway chưa cấu hình Google OAuth" }
            val token = tokenRequest(client, mapOf(
                "client_id" to client.clientId,
                "client_secret" to client.clientSecret,
                "code" to code,
                "grant_type" to "authorization_code",
                "redirect_uri" to client.redirectUri
            ))
            val normalized = normalizeToken(token, null)
            credentials.saveAccount(session.remoteId, session.displayName, normalized.toString())
            session.current = session.snapshot("COMPLETE", null, null)
            proxyResponse(200, "Đã kết nối Google Drive. Có thể đóng trang này.")
        }.getOrElse { error ->
            session.current = session.snapshot("ERROR", null, error.message ?: "Google OAuth thất bại")
            proxyResponse(400, session.current.error.orEmpty())
        }
    }

    suspend fun accessToken(accountId: String, forceRefresh: Boolean = false): String {
        val stored = JSONObject(requireNotNull(credentials.token(accountId)) { "Drive chưa có OAuth token" })
        val accessToken = stored.optString("access_token")
        val expiresAt = stored.optLong("expires_at_ms", 0L)
        if (!forceRefresh && accessToken.isNotBlank() && (expiresAt == 0L || expiresAt > nowMs() + REFRESH_MARGIN_MS)) {
            return accessToken
        }
        val refreshToken = stored.optString("refresh_token")
        require(refreshToken.isNotBlank()) { "OAuth token đã hết hạn và không có refresh token" }
        val client = requireNotNull(credentials.oauthClient()) { "Gateway chưa cấu hình Google OAuth" }
        val refreshed = tokenRequest(client, mapOf(
            "client_id" to client.clientId,
            "client_secret" to client.clientSecret,
            "refresh_token" to refreshToken,
            "grant_type" to "refresh_token"
        ))
        val normalized = normalizeToken(refreshed, refreshToken)
        credentials.updateToken(accountId, normalized.toString())
        return normalized.getString("access_token")
    }

    private suspend fun tokenRequest(client: GoogleOAuthClient, values: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val connection = URL(client.tokenUri).openConnection() as HttpURLConnection
            try {
                val body = form(*values.map { it.key to it.value }.toTypedArray()).toByteArray(Charsets.UTF_8)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.setRequestProperty("Accept", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                val payload = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                require(code in 200..299) { "Google OAuth HTTP $code: ${payload.take(300)}" }
                JSONObject(payload)
            } finally {
                connection.disconnect()
            }
        }

    private fun normalizeToken(token: JSONObject, existingRefreshToken: String?): JSONObject =
        JSONObject(token.toString()).apply {
            val refresh = optString("refresh_token").ifBlank { existingRefreshToken.orEmpty() }
            if (refresh.isNotBlank()) put("refresh_token", refresh)
            val expiresIn = optLong("expires_in", 0L)
            if (expiresIn > 0) put("expires_at_ms", nowMs() + expiresIn * 1_000L)
        }

    private fun query(path: String): Map<String, String> {
        val raw = URI("http://localhost$path").rawQuery.orEmpty()
        return raw.split('&').filter { it.isNotBlank() }.associate { part ->
            val pieces = part.split('=', limit = 2)
            decode(pieces[0]) to decode(pieces.getOrElse(1) { "" })
        }
    }

    private fun proxyResponse(status: Int, message: String): JSONObject {
        val body = message.toByteArray(Charsets.UTF_8)
        return JSONObject()
            .put("status", status)
            .put("headers", JSONObject().put("Content-Type", "text/plain; charset=utf-8"))
            .put("body_base64", Base64.getEncoder().encodeToString(body))
    }

    private fun form(vararg values: Pair<String, String>): String =
        values.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun decode(value: String) = URLDecoder.decode(value, Charsets.UTF_8.name())

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private data class PendingSession(
        val sessionId: String,
        val remoteId: String,
        val displayName: String,
        val state: String
    ) {
        @Volatile
        var current = snapshot("WAITING_BROWSER", null, null)

        fun snapshot(status: String, authorizationUrl: String?, error: String?) =
            DriveOAuthSession(sessionId, remoteId, displayName, status, authorizationUrl, error)
    }

    private companion object {
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val REFRESH_MARGIN_MS = 60_000L
        val ID = Regex("[a-z0-9_-]{1,32}")
        val RANDOM = SecureRandom()
    }
}
