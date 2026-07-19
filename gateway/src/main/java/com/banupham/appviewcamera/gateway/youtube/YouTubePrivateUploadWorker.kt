package com.banupham.appviewcamera.gateway.youtube

import com.banupham.appviewcamera.gateway.recording.RecordingRepository
import com.banupham.appviewcamera.gateway.storage.GoogleDriveUploadWorker
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class YouTubePrivateUploadWorker(
    private val credentials: YouTubeCredentialStore,
    private val oauth: YouTubeOAuthManager,
    private val recordings: RecordingRepository,
    private val drive: GoogleDriveUploadWorker
) {
    suspend fun uploadPending() {
        if (credentials.config() == null || credentials.accounts().isEmpty()) return
        recordings.youtubeCandidates().forEach { clip ->
            recordings.markYouTubeUploading(clip)
            runCatching {
                val file = recordings.localFile(clip) ?: drive.restoreForPlayback(clip.id)
                    ?: error("Không thể khôi phục clip để upload YouTube")
                val (accountId, accessToken) = oauth.accessToken()
                val videoId = try {
                    upload(accessToken, file, clip.cameraId, clip.startedAtMs)
                } catch (error: YouTubeHttpException) {
                    if (error.statusCode != 401) throw error
                    val refreshed = oauth.accessToken(accountId, forceRefresh = true).second
                    upload(refreshed, file, clip.cameraId, clip.startedAtMs)
                }
                recordings.markYouTubeReady(clip, videoId)
            }.onFailure { error ->
                recordings.markYouTubeFailed(clip, error.message ?: "YouTube upload thất bại")
            }
        }
    }

    private suspend fun upload(
        accessToken: String,
        file: File,
        cameraId: String,
        startedAtMs: Long
    ): String = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() > 0) { "Clip YouTube không hợp lệ" }
        val uploadUrl = startSession(accessToken, file.length(), cameraId, startedAtMs)
        var uploaded = 0L
        RandomAccessFile(file, "r").use { input ->
            while (uploaded < file.length()) {
                val size = minOf(CHUNK_BYTES.toLong(), file.length() - uploaded).toInt()
                val bytes = ByteArray(size)
                input.seek(uploaded)
                input.readFully(bytes)
                val end = uploaded + size - 1
                val connection = URL(uploadUrl).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "PUT"
                    connection.doOutput = true
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 120_000
                    connection.setRequestProperty("Authorization", "Bearer $accessToken")
                    connection.setRequestProperty("Content-Type", "video/mp4")
                    connection.setRequestProperty("Content-Range", "bytes $uploaded-$end/${file.length()}")
                    connection.setFixedLengthStreamingMode(size)
                    connection.outputStream.use { it.write(bytes) }
                    val code = connection.responseCode
                    val payload = (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    when (code) {
                        200, 201 -> {
                            val videoId = runCatching { JSONObject(payload).optString("id") }.getOrDefault("")
                            require(videoId.isNotBlank()) { "YouTube hoàn tất nhưng không trả video ID" }
                            return@withContext videoId
                        }
                        308 -> uploaded = uploadedFromRange(connection.getHeaderField("Range"), end + 1)
                        else -> throw YouTubeHttpException(code, "YouTube upload HTTP $code: ${payload.take(300)}")
                    }
                } finally {
                    connection.disconnect()
                }
            }
        }
        error("YouTube resumable upload chưa hoàn tất")
    }

    private fun startSession(
        accessToken: String,
        sizeBytes: Long,
        cameraId: String,
        startedAtMs: Long
    ): String {
        val metadata = JSONObject()
            .put("snippet", JSONObject()
                .put("title", "AppView $cameraId $startedAtMs")
                .put("description", "AppViewCamera private recording")
                .put("categoryId", "22"))
            .put("status", JSONObject()
                .put("privacyStatus", "private")
                .put("selfDeclaredMadeForKids", false))
            .toString()
            .toByteArray()
        val connection = URL(
            "https://www.googleapis.com/upload/youtube/v3/videos" +
                "?uploadType=resumable&part=snippet%2Cstatus&notifySubscribers=false"
        ).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("X-Upload-Content-Length", sizeBytes.toString())
            connection.setRequestProperty("X-Upload-Content-Type", "video/mp4")
            connection.setFixedLengthStreamingMode(metadata.size)
            connection.outputStream.use { it.write(metadata) }
            val code = connection.responseCode
            val payload = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw YouTubeHttpException(code, "YouTube session HTTP $code: ${payload.take(300)}")
            return connection.getHeaderField("Location")
                ?: error("YouTube không trả resumable upload URL")
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadedFromRange(value: String?, fallback: Long): Long =
        value?.substringAfterLast('-')?.toLongOrNull()?.plus(1L) ?: fallback

    private class YouTubeHttpException(val statusCode: Int, message: String) : Exception(message)

    private companion object {
        const val CHUNK_BYTES = 8 * 1024 * 1024
    }
}
