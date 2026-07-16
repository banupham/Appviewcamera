package com.banupham.appviewcamera.gateway.rtsp

import android.content.Context
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.banupham.appviewcamera.gateway.camera.Camera
import com.banupham.appviewcamera.gateway.security.CredentialCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@UnstableApi
class Media3RtspCameraProbe(
    context: Context,
    private val credentialCipher: CredentialCipher,
    private val timeoutMillis: Long = 12_000
) : CameraProbe {
    private val applicationContext = context.applicationContext

    override suspend fun probe(
        camera: Camera,
        onRetry: (attempt: Int, delayMillis: Long) -> Unit
    ): CameraProbeResult {
        var lastFailure: Throwable? = null
        repeat(RetrySchedule.MAX_ATTEMPTS) { index ->
            val attempt = index + 1
            val forceTcp = index % 2 == 0
            try {
                Log.i(LOG_TAG, "Probe cameraId=${camera.id}, attempt=$attempt, transport=${transportName(forceTcp)}")
                return probeOnce(camera, forceTcp)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                lastFailure = failure
                val safeMessage = CredentialRedactor.redact(failure.message)
                Log.w(LOG_TAG, "Probe failed cameraId=${camera.id}, attempt=$attempt: $safeMessage")
                if (attempt < RetrySchedule.MAX_ATTEMPTS) {
                    val retryDelay = RetrySchedule.delayAfterFailure(attempt)
                    onRetry(attempt + 1, retryDelay)
                    delay(retryDelay)
                }
            }
        }
        throw CameraProbeException(CredentialRedactor.redact(lastFailure?.message), lastFailure)
    }

    private suspend fun probeOnce(camera: Camera, forceTcp: Boolean): CameraProbeResult = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<CameraProbeResult>()
        val player = ExoPlayer.Builder(applicationContext).build()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    player.videoFormat?.let { format -> result.complete(format.toProbeResult()) }
                        ?: result.completeExceptionally(CameraProbeException("Luồng RTSP không có video"))
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                result.completeExceptionally(
                    CameraProbeException(RtspFailureMessage.from(error, transportName(forceTcp)), error)
                )
            }
        }

        try {
            player.addListener(listener)
            player.volume = 0f
            val password = credentialCipher.decrypt(camera.encryptedPassword)
            val securedUrl = RtspUrlFactory.withCredentials(camera.mainRtspUrl, camera.username, password)
            val mediaSource = RtspMediaSource.Factory()
                .setForceUseRtpTcp(forceTcp)
                .createMediaSource(MediaItem.fromUri(securedUrl))
            player.setMediaSource(mediaSource)
            player.prepare()
            player.playWhenReady = true
            withTimeout(timeoutMillis) { result.await() }
        } finally {
            player.removeListener(listener)
            player.release()
        }
    }

    private fun Format.toProbeResult() = CameraProbeResult(
        codec = codecs ?: sampleMimeType ?: "unknown",
        width = width.takeIf { it > 0 },
        height = height.takeIf { it > 0 },
        fps = frameRate.takeIf { it > 0f },
        bitrate = averageBitrate.takeIf { it != Format.NO_VALUE && it > 0 }
            ?: peakBitrate.takeIf { it != Format.NO_VALUE && it > 0 }
    )

    private companion object {
        const val LOG_TAG = "Gateway/RTSP"
        fun transportName(forceTcp: Boolean): String = if (forceTcp) "TCP" else "AUTO/UDP"
    }
}

object RtspFailureMessage {
    fun from(error: Throwable, transport: String): String {
        val messages = generateSequence(error) { it.cause }
            .mapNotNull(Throwable::message)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        val combined = messages.joinToString(" • ")
        val category = when {
            AUTH_PATTERN.containsMatchIn(combined) -> "Sai tài khoản/mật khẩu hoặc camera từ chối xác thực"
            NOT_FOUND_PATTERN.containsMatchIn(combined) -> "Sai RTSP path hoặc luồng không tồn tại"
            TIMEOUT_PATTERN.containsMatchIn(combined) -> "Camera không phản hồi kịp thời"
            NETWORK_PATTERN.containsMatchIn(combined) -> "Không kết nối được IP/cổng camera"
            else -> "Không mở được luồng RTSP"
        }
        val code = (error as? PlaybackException)?.errorCodeName
        val detail = combined.takeIf(String::isNotBlank)?.let(CredentialRedactor::redact)
        return buildString {
            append(category).append(" [").append(transport)
            if (!code.isNullOrBlank()) append(", ").append(code)
            append(']')
            if (!detail.isNullOrBlank() && detail != category) append(": ").append(detail.take(300))
        }
    }

    private val AUTH_PATTERN = Regex("(?i)\\b(401|403|unauthori[sz]ed|forbidden|auth(?:entication)?)\\b")
    private val NOT_FOUND_PATTERN = Regex("(?i)\\b(404|not found|describe failed|no media)\\b")
    private val TIMEOUT_PATTERN = Regex("(?i)(timeout|timed out|deadline)")
    private val NETWORK_PATTERN = Regex("(?i)(connect|connection refused|unreachable|unknown host|dns|socket)")
}

class CameraProbeException(message: String, cause: Throwable? = null) : Exception(message, cause)
