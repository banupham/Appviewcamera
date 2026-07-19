package com.banupham.appviewcamera.gateway.storage

import com.banupham.appviewcamera.gateway.recording.RecordingRepository
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class GoogleDriveUploadWorker(
    private val credentials: CloudCredentialStore,
    private val recordings: RecordingRepository,
    private val oauth: GoogleDriveOAuthManager
) {
    suspend fun uploadPending() {
        val account = credentials.accounts().firstOrNull { it.active } ?: return
        recordings.uploadCandidates().forEach { clip ->
            val file = recordings.localFile(clip) ?: return@forEach
            runCatching {
                val result = authenticated(account.id) { accessToken ->
                    upload(accessToken, file, "AppView/${clip.relativePath}")
                }
                require(result.sizeBytes == file.length()) { "Kích thước Drive không khớp file local" }
                recordings.markDriveUploaded(clip, account.id, result.path, result.fileId, result.sizeBytes)
            }.onFailure { error ->
                recordings.markDriveFailed(clip, error.message ?: "Google Drive upload thất bại")
            }
        }
    }

    suspend fun restoreForPlayback(clipId: String): File? {
        val clip = recordings.get(clipId) ?: return null
        recordings.localFile(clip)?.let { return it }
        val accountId = clip.remoteId ?: return null
        val remoteFileId = clip.remoteFileId ?: return null
        val target = recordings.restoreTarget(clip)
        return runCatching {
            authenticated(accountId) { accessToken -> download(accessToken, remoteFileId, target) }
            require(target.length() == clip.sizeBytes) { "Kích thước playback cache không khớp Drive" }
            recordings.markLocalRestored(clip)
            target
        }.getOrNull()
    }

    private suspend fun download(accessToken: String, fileId: String, target: File) = withContext(Dispatchers.IO) {
        val part = File(target.parentFile, target.name + ".part")
        val connection = (URL("https://www.googleapis.com/drive/v3/files/" + fileId + "?alt=media")
            .openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("Authorization", "Bearer " + accessToken)
            val code = connection.responseCode
            if (code !in 200..299) {
                val payload = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw DriveHttpException(code, "Drive download HTTP $code: ${payload.take(300)}")
            }
            connection.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            require(part.renameTo(target)) { "Không thể hoàn tất playback cache" }
        } finally {
            connection.disconnect()
            if (part.exists()) part.delete()
        }
    }

    private suspend fun upload(accessToken: String, file: File, remotePath: String): DriveUpload =
        withContext(Dispatchers.IO) {
            val boundary = "appview-${UUID.randomUUID()}"
            val connection = (URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                .openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 120_000
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                connection.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
                connection.outputStream.buffered().use { output ->
                    output.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
                    output.write(JSONObject().put("name", remotePath.substringAfterLast('/')).toString().toByteArray())
                    output.write("\r\n--$boundary\r\nContent-Type: video/mp4\r\n\r\n".toByteArray())
                    file.inputStream().use { it.copyTo(output) }
                    output.write("\r\n--$boundary--\r\n".toByteArray())
                }
                val code = connection.responseCode
                val payload = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw DriveHttpException(code, "Drive HTTP $code: ${payload.take(300)}")
                val json = JSONObject(payload)
                DriveUpload(json.getString("id"), remotePath, file.length())
            } finally {
                connection.disconnect()
            }
        }

    private suspend fun <T> authenticated(accountId: String, block: suspend (String) -> T): T {
        val accessToken = oauth.accessToken(accountId)
        return try {
            block(accessToken)
        } catch (error: DriveHttpException) {
            if (error.statusCode != 401) throw error
            block(oauth.accessToken(accountId, forceRefresh = true))
        }
    }

    private class DriveHttpException(val statusCode: Int, message: String) : Exception(message)

    private data class DriveUpload(val fileId: String, val path: String, val sizeBytes: Long)
}
