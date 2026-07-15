package com.banupham.appviewcamera.viewer.player

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.banupham.appviewcamera.viewer.live.RtspRetryPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class RtspTileState { LOADING, ONLINE, OFFLINE, RETRY }

@OptIn(UnstableApi::class)
@Composable
fun RtspPlayer(
    url: String,
    modifier: Modifier = Modifier,
    showControls: Boolean = true
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var tileState by remember(url) { mutableStateOf(RtspTileState.LOADING) }
    var detail by remember(url) { mutableStateOf<String?>(null) }
    val player = remember(context, url) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 1_500, 250, 500)
            .build()
        ExoPlayer.Builder(context)
            .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setLoadControl(loadControl)
            .build()
    }

    DisposableEffect(player, url, lifecycleOwner) {
        var retryJob: Job? = null
        var attempt = 0
        var needsPrepare = true

        fun prepare() {
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                needsPrepare = true
                return
            }
            runCatching {
                tileState = if (attempt == 0) RtspTileState.LOADING else RtspTileState.RETRY
                val source = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .createMediaSource(MediaItem.fromUri(url))
                player.stop()
                player.setMediaSource(source)
                player.prepare()
                player.playWhenReady = true
                needsPrepare = false
            }.onFailure { failure ->
                tileState = RtspTileState.OFFLINE
                detail = failure.message
                needsPrepare = true
            }
        }

        val listener = object : Player.Listener {
            override fun onPlayerError(playbackError: PlaybackException) {
                tileState = RtspTileState.OFFLINE
                detail = playbackError.message
                needsPrepare = true
                val wait = RtspRetryPolicy.delayMillis(attempt++)
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay(wait)
                    tileState = RtspTileState.RETRY
                    prepare()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> tileState = RtspTileState.LOADING
                    Player.STATE_READY -> {
                        attempt = 0
                        detail = null
                        tileState = RtspTileState.ONLINE
                    }
                }
            }
        }
        val lifecycleObserver = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (needsPrepare || player.mediaItemCount == 0) prepare() else player.play()
            }

            override fun onStop(owner: LifecycleOwner) {
                retryJob?.cancel()
                player.pause()
            }
        }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        prepare()
        onDispose {
            retryJob?.cancel()
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(listener)
            player.release()
        }
    }

    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = showControls
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = {
                it.player = player
                it.useController = showControls
            }
        )
        if (tileState != RtspTileState.ONLINE) {
            Text(
                text = when (tileState) {
                    RtspTileState.LOADING -> "Đang tải…"
                    RtspTileState.RETRY -> "Đang kết nối lại…"
                    RtspTileState.OFFLINE -> "Camera ngoại tuyến${detail?.let { ": $it" }.orEmpty()}"
                    RtspTileState.ONLINE -> ""
                },
                color = if (tileState == RtspTileState.OFFLINE) MaterialTheme.colorScheme.error else Color.White
            )
        }
    }
}
