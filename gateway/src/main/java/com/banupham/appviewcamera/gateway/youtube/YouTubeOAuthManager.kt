package com.banupham.appviewcamera.gateway.youtube

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class YouTubeOAuthSession(
    val sessionId: String,
    val accountId: String,
    val displayName: String,
    val status: String,
    val authorizationUrl: String?,
    val error: String?
) {
    fun json(): JSONObject = JSONObject()
        .put("session_id", sessionId)
        .put("account_id", accountId)
        .put("display_name", displayName)
        .put("status", status)
        .put("authorization_url", authorizationUrl ?: JSONObject.NULL)
        .put("error", error ?: JSONObject.NULL)
}

class YouTubeOAuthManager(
    private val credentials: YouTubeCredentialStore,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val sessions = ConcurrentHashMap<String, PendingSession>()

    fun start(accountId: String, displayName: String): YouTubeOAuthSession {
        require(ID.matches(accountId)) { "ID YouTube không hợp lệ" }
        val config = requireNotNull(credentials.config()) { "Gateway chưa cấu hình YouTube OAuth" }
        cleanup()
        val verifier = randomToken(64)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        )
        val pending = PendingSession(
            sessionId = randomToken(24),
            accountId = accountId,
            displayName = displayName.ifBlank { accountId },
            state = randomToken(24),
            verifier = verifier,
            createdAtMs = nowMs()
        )
        val url = AUTH_URI + "?" + form(
            "client_id" to config.clientId,
            "redirect_uri" to REDIRECT_URI,
            "response_type" to "code",
            "scope" to YOUTUBE_SCOPE,
            "access_type" to "offline",
            "prompt" to "consent",
            "include_granted_scopes" to "true",
            "state" to pending.state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256"
        )
        pending.current = pending.snapshot("WAITING_BROWSER", url, null)
        sessions[pending.sessionId] = pending
        return pending.current
    }

    fun reconnect(accountId: String): YouTubeOAuthSession {
        val account = credentials.accounts().firstOrNull { it.id == accountId }
            ?: error("Không tìm thấy tài khoản YouTube")
        return start(account.id, account.displayName)
    }

    fun status(sessionId: String): YouTubeOAuthSession =
        requireNotNull(sessions[sessionId]) { "Phiên OAuth YouTube đã hết hạn" }.current

    suspend fun callback(sessionId: String, callbackPath: String): JSONObject {
        val pending = requireNotNull(sessions[sessionId]) { "Phiên OAuth YouTube đã hết hạn" }
        return runCatching {
            require(pending.current.status == "WAITING_BROWSER") { "Phiên OAuth không chờ callback" }
            val query = query(callbackPath)
            require(query["state"] == pending.state) { "OAuth state không hợp lệ" }
            query["error"]?.let { error("Google OAuth từ chối: $it") }
            val code = requireNotNull(query["code"]) { "OAuth callback thiếu authorization code" }
            val config = requireNotNull(credentials.config()) { "Gateway chưa cấu hình YouTube OAuth" }
            val token = tokenRequest(mapOf(
                "client_id" to config.clientId,
                "client_secret" to config.clientSecret,
                "code" to code,
                "code_verifier" to pending.verifier,
                "redirect_uri" to REDIRECT_URI,
                "grant_type" to "authorization_code"
            ))
            val normalized = normalizeToken(token, null)
            credentials.saveAccount(pending.accountId, pending.displayName, normalized)
            pending.current = pending.snapshot("COMPLETE", null, null)
            browserResponse(200, "Đã kết nối YouTube Private. Có thể đóng trang này.")
        }.getOrElse { error ->
            pending.current = pending.snapshot("ERROR", null, error.message ?: "YouTube OAuth thất bại")
            browserResponse(400, pending.current.error.orEmpty())
        }
    }

    suspend fun accessToken(accountId: String? = null, forceRefresh: Boolean = false): Pair<String, String> {
        val account = if (accountId == null) credentials.accounts().firstOrNull { it.active }
            else credentials.accounts().firstOrNull { it.id == accountId }
        requireNotNull(account) { "Chưa có tài khoản YouTube" }
        val stored = requireNotNull(credentials.token(account.id)) { "Tài khoản YouTube thiếu token" }
        val access = stored.optString("access_token")
        val expiresAt = stored.optLong("expires_at_ms", 0L)
        if (!forceRefresh && access.isNotBlank() && expiresAt > nowMs() + 60_000L) return account.id to access
        val refresh = stored.optString("refresh_token")
        require(refresh.isNotBlank()) { "Tài khoản YouTube cần kết nối lại" }
        val config = requireNotNull(credentials.config()) { "Gateway chưa cấu hình YouTube OAuth" }
        val token = tokenRequest(mapOf(
            "client_id" to config.clientId,
            "client_secret" to config.clientSecret,
            "refresh_token" to refresh,
            "grant_type" to "refresh_token"
        ))
        val normalized = normalizeToken(token, refresh)
        credentials.updateToken(account.id, normalized)
        return account.id to normalized.getString("access_token")
    }

    private suspend fun tokenRequest(values: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL(TOKEN_URI).openConnection() as HttpURLConnection
        try {
            val body = form(*values.map { it.key to it.value }.toTypedArray()).toByteArray()
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val payload = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            require(code in 200..299) { "YouTube OAuth HTTP $code: ${payload.take(300)}" }
            JSONObject(payload)
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeToken(token: JSONObject, existingRefresh: String?): JSONObject =
        JSONObject(token.toString()).apply {
            val refresh = optString("refresh_token").ifBlank { existingRefresh.orEmpty() }
            require(refresh.isNotBlank()) { "Google không trả refresh token YouTube" }
            put("refresh_token", refresh)
            val expiresIn = optLong("expires_in", 3_600L)
            put("expires_at_ms", nowMs() + expiresIn * 1_000L)
        }

    private fun query(path: String): Map<String, String> =
        URI("http://localhost$path").rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate {
            val pieces = it.split('=', limit = 2)
            decode(pieces[0]) to decode(pieces.getOrElse(1) { "" })
        }

    private fun browserResponse(status: Int, message: String): JSONObject {
        val body = message.toByteArray()
        return JSONObject()
            .put("status", status)
            .put("headers", JSONObject().put("Content-Type", "text/plain; charset=utf-8"))
            .put("body_base64", Base64.getEncoder().encodeToString(body))
    }

    private fun cleanup() {
        val cutoff = nowMs() - 10 * 60_000L
        sessions.entries.removeIf { it.value.createdAtMs < cutoff }
    }

    private fun form(vararg values: Pair<String, String>) =
        values.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun decode(value: String) = URLDecoder.decode(value, Charsets.UTF_8.name())

    private fun randomToken(size: Int): String {
        val bytes = ByteArray(size)
        RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private data class PendingSession(
        val sessionId: String,
        val accountId: String,
        val displayName: String,
        val state: String,
        val verifier: String,
        val createdAtMs: Long
    ) {
        @Volatile lateinit var current: YouTubeOAuthSession
        fun snapshot(status: String, url: String?, error: String?) =
            YouTubeOAuthSession(sessionId, accountId, displayName, status, url, error)
    }

    private companion object {
        const val AUTH_URI = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URI = "https://oauth2.googleapis.com/token"
        const val REDIRECT_URI = "http://localhost:53683/"
        const val YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube.upload"
        val ID = Regex("[a-z0-9_-]{1,32}")
        val RANDOM = SecureRandom()
    }
}
