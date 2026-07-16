package com.banupham.appviewcamera.gateway.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.banupham.appviewcamera.gateway.GatewayApplication
import com.banupham.appviewcamera.gateway.media.MediaMtxSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class GatewayServerService : Service() {
    private var httpServer: GatewayHttpServer? = null
    private var mediaMtx: MediaMtxSupervisor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cameraWatchJob: Job? = null
    private var recordingScanJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val container get() = (application as GatewayApplication).container

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                stopServers()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                stopServers()
                startServers()
            }
            else -> startServers()
        }
        return START_STICKY
    }

    private fun startServers() {
        if (httpServer != null || mediaMtx != null) return
        startForeground(NOTIFICATION_ID, notification("API :8080 • RTSP :8554"))
        val settings = container.gatewaySettings.settings.value
        runCatching {
            acquireLocks()
            val media = MediaMtxSupervisor(
                this,
                container.cameraRepository,
                container.gatewayRuntimeState,
                settings.rtspPort,
                recordingEnabled = { container.recordingSettings.get().effectiveEnabled }
            )
            val http = GatewayHttpServer(
                settings,
                container.cameraRepository,
                container.gatewayRuntimeState,
                container.recordingRepository,
                container.recordingSettings,
                onRecordingSettingsChanged = {
                    media.reconfigure(container.cameraRepository.list())
                }
            )
            try {
                media.start(runBlocking { container.cameraRepository.list() })
                http.start()
            } catch (error: Throwable) {
                media.close()
                http.close()
                throw error
            }
            httpServer = http
            mediaMtx = media
            cameraWatchJob = serviceScope.launch {
                container.cameraRepository.observeAll().drop(1).collect(media::reconfigure)
            }
            recordingScanJob = serviceScope.launch {
                while (true) {
                    val cameraIds = container.cameraRepository.list().map { it.relayPath }.toSet()
                    val retentionMinutes = container.recordingSettings.get().localRetentionMinutes
                    runCatching { container.recordingRepository.scan(cameraIds, retentionMinutes) }.getOrNull()?.let { status ->
                        if (status.storagePressure && container.recordingSettings.get().effectiveEnabled) {
                            container.recordingSettings.pauseForStorage()
                            media.reconfigure(container.cameraRepository.list())
                        }
                    }
                    delay(RECORDING_SCAN_INTERVAL_MS)
                }
            }
            container.gatewayRuntimeState.started()
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification("API :${settings.apiPort} • RTSP :${settings.rtspPort}"))
        }.onFailure { error ->
            stopServers()
            container.gatewayRuntimeState.failed(error.message ?: "Không thể khởi động Gateway")
            stopSelf()
        }
    }

    private fun stopServers() {
        cameraWatchJob?.cancel()
        cameraWatchJob = null
        recordingScanJob?.cancel()
        recordingScanJob = null
        runCatching { mediaMtx?.close() }
        runCatching { httpServer?.close() }
        mediaMtx = null
        httpServer = null
        releaseLocks()
        container.gatewayRuntimeState.stopped()
    }

    private fun acquireLocks() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:gateway").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:gateway").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Camera Gateway server",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Giữ API và RTSP proxy hoạt động trong mạng LAN" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(detail: String): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.presence_video_online)
        .setContentTitle("Camera Gateway đang chạy")
        .setContentText(detail)
        .setOngoing(true)
        .setCategory(Notification.CATEGORY_SERVICE)
        .build()

    override fun onDestroy() {
        stopServers()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "gateway_server"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.banupham.appviewcamera.gateway.START"
        private const val ACTION_STOP = "com.banupham.appviewcamera.gateway.STOP"
        private const val ACTION_RESTART = "com.banupham.appviewcamera.gateway.RESTART"
        private const val RECORDING_SCAN_INTERVAL_MS = 30_000L

        fun start(context: Context) = ContextCompat.startForegroundService(
            context,
            Intent(context, GatewayServerService::class.java).setAction(ACTION_START)
        )

        fun stop(context: Context) = context.startService(
            Intent(context, GatewayServerService::class.java).setAction(ACTION_STOP)
        )

        fun restart(context: Context) = ContextCompat.startForegroundService(
            context,
            Intent(context, GatewayServerService::class.java).setAction(ACTION_RESTART)
        )
    }
}
