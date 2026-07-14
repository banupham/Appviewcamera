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

@OptIn(UnstableApi::class)
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
            try {
                Log.i(LOG_TAG, "Probe cameraId=${camera.id}, attempt=$attempt")
                return probeOnce(camera)
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

    private suspend fun probeOnce(camera: Camera): CameraProbeResult = withContext(Dispatchers.Main) {
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
                result.completeExceptionally(CameraProbeException(CredentialRedactor.redact(error.message), error))
            }
        }

        try {
            player.addListener(listener)
            player.volume = 0f
            val password = credentialCipher.decrypt(camera.encryptedPassword)
            val securedUrl = RtspUrlFactory.withCredentials(camera.mainRtspUrl, camera.username, password)
            val mediaSource = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(securedUrl))
            player.setMediaSource(mediaSource)
            player.prepare()
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
    }
}

class CameraProbeException(message: String, cause: Throwable? = null) : Exception(message, cause)
