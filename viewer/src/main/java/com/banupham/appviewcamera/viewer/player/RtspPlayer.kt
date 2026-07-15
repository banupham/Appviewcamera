package com.banupham.appviewcamera.viewer.player

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@Composable
fun RtspPlayer(
    url: String,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var error by remember(url) { mutableStateOf<String?>(null) }
    val player = remember(context, url) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 1_500, 250, 500)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
    }

    DisposableEffect(player, url) {
        var retryJob: Job? = null
        var attempt = 0
        fun prepare() {
            runCatching {
                val source = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .createMediaSource(MediaItem.fromUri(url))
                player.setMediaSource(source)
                player.prepare()
                player.playWhenReady = true
            }.onFailure { failure ->
                error = "Không thể mở camera: ${failure.message ?: "URL RTSP không hợp lệ"}"
            }
        }
        val listener = object : Player.Listener {
            override fun onPlayerError(playbackError: PlaybackException) {
                error = "Mất kết nối, đang thử lại…"
                val delays = longArrayOf(1_000, 3_000, 5_000, 10_000)
                val wait = delays[minOf(attempt, delays.lastIndex)]
                attempt++
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay(wait)
                    prepare()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    attempt = 0
                    error = null
                }
            }
        }
        player.addListener(listener)
        prepare()
        onDispose {
            retryJob?.cancel()
            player.removeListener(listener)
            player.release()
        }
    }

    Box(
        modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PlayerView(context).apply {
                    this.player = player
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { it.player = player }
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
