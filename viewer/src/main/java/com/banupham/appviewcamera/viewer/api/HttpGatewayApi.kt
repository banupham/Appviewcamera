package com.banupham.appviewcamera.viewer.api

import com.banupham.appviewcamera.viewer.settings.GatewayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

class HttpGatewayApi(private val config: GatewayConfig) : GatewayApi {
    override suspend fun status(): GatewayStatus = GatewayJsonParser.status(request("GET", "/api/status"))

    override suspend fun cameras(): List<CameraSummary> = GatewayJsonParser.cameras(request("GET", "/api/cameras"))

    override suspend fun scanCameras(): List<DiscoveryCandidate> = GatewayJsonParser.candidates(
        request("POST", "/api/discovery/scan", readTimeoutMs = DISCOVERY_TIMEOUT_MS)
    )

    override suspend fun discoveryCandidates(): List<DiscoveryCandidate> = GatewayJsonParser.candidates(
        request("GET", "/api/discovery/candidates")
    )

    override suspend fun saveCamera(camera: CameraMutation): CameraSummary {
        val body = JSONObject().apply {
            put("id", camera.id)
            put("name", camera.name)
            put("host", camera.host)
            put("port", camera.port)
            put("username", camera.username)
            if (!camera.password.isNullOrEmpty()) put("password", camera.password)
            put("main_path", camera.mainPath)
            put("sub_path", camera.subPath)
            put("relay_path", camera.relayPath)
            put("enabled", camera.enabled)
            put("record_enabled", camera.recordEnabled)
            put("motion_enabled", camera.motionEnabled)
            put("audio_enabled", camera.audioEnabled)
        }.toString()
        return GatewayJsonParser.cameras("[${request("PUT", "/api/cameras/${encode(camera.id)}", body)}]").single()
    }

    override suspend fun deleteCamera(cameraId: String) {
        request("DELETE", "/api/cameras/${encode(cameraId)}")
    }

    override suspend fun drives(): List<GoogleDriveAccount> =
        GatewayJsonParser.drives(request("GET", "/api/storage/drives"))

    override suspend fun addDrive(drive: GoogleDriveMutation): GoogleDriveAccount {
        val body = JSONObject().apply {
            put("id", drive.id)
            put("display_name", drive.displayName)
            put("oauth_token", drive.oauthToken)
        }.toString()
        return GatewayJsonParser.drives("[${request("POST", "/api/storage/drives", body)}]").single()
    }

    override suspend fun refreshDrive(driveId: String): GoogleDriveAccount =
        GatewayJsonParser.drives(
            "[${request("POST", "/api/storage/drives/${encode(driveId)}/refresh", readTimeoutMs = STORAGE_TIMEOUT_MS)}]"
        ).single()

    override suspend fun deleteDrive(driveId: String) {
        request("DELETE", "/api/storage/drives/${encode(driveId)}")
    }

    override suspend fun activateDrive(driveId: String): GoogleDriveAccount =
        GatewayJsonParser.drives(
            "[${request("POST", "/api/storage/drives/${encode(driveId)}/activate")}]"
        ).single()

    override suspend fun recordingStatus(): RecordingStatus =
        GatewayJsonParser.recordingStatus(request("GET", "/api/recording"))

    override suspend fun updateRecording(enabled: Boolean, localRetentionMinutes: Int): RecordingStatus {
        val body = JSONObject().apply {
            put("enabled", enabled)
            put("local_retention_minutes", localRetentionMinutes)
        }.toString()
        return GatewayJsonParser.recordingStatus(request("PUT", "/api/recording", body, STORAGE_TIMEOUT_MS))
    }

    override suspend fun recordings(cameraId: String?, fromMs: Long?, toMs: Long?): List<RecordingClip> {
        val parameters = buildList {
            cameraId?.let { add("camera_id=${encode(it)}") }
            fromMs?.let { add("from_ms=$it") }
            toMs?.let { add("to_ms=$it") }
        }
        val suffix = parameters.takeIf { it.isNotEmpty() }?.joinToString("&", prefix = "?").orEmpty()
        return GatewayJsonParser.recordings(request("GET", "/api/recordings$suffix", readTimeoutMs = STORAGE_TIMEOUT_MS))
    }

    override suspend fun protectRecording(recordingId: String, protected: Boolean) {
        val body = JSONObject().put("protected", protected).toString()
        request("PUT", "/api/recordings/${encode(recordingId)}/protection", body)
    }

    private suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        readTimeoutMs: Int = READ_TIMEOUT_MS
    ): String = withContext(Dispatchers.IO) {
        val connection = URL(config.apiBaseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Authorization", "Bearer ${config.apiToken}")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) {
                val message = response.take(MAX_ERROR_LENGTH).ifBlank { "HTTP $statusCode" }
                throw IOException("Gateway trả lỗi $statusCode: $message")
            }
            response
        } catch (error: IOException) {
            throw IOException("Không kết nối được Gateway tại ${config.host}:${config.apiPort}: ${error.message}", error)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 8_000
        const val DISCOVERY_TIMEOUT_MS = 180_000
        const val STORAGE_TIMEOUT_MS = 60_000
        const val MAX_ERROR_LENGTH = 300

        fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
