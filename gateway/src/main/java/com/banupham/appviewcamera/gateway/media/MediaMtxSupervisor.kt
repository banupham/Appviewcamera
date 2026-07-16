package com.banupham.appviewcamera.gateway.media

import android.content.Context
import com.banupham.appviewcamera.gateway.camera.Camera
import com.banupham.appviewcamera.gateway.camera.CameraRepository
import com.banupham.appviewcamera.gateway.server.GatewayRuntimeState
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MediaMtxSupervisor(
    context: Context,
    repository: CameraRepository,
    private val runtimeState: GatewayRuntimeState,
    rtspPort: Int,
    recordingEnabled: () -> Boolean
) : Closeable {
    private val binary = File(context.applicationInfo.nativeLibraryDir, "libmediamtx.so")
    private val runtimeDirectory = File(context.noBackupFilesDir, "mediamtx")
    private val configFile = File(runtimeDirectory, "mediamtx.yml")
    private val logFile = File(context.filesDir, "logs/mediamtx.log")
    private val configWriter = MediaMtxConfigWriter(
        repository::decryptPassword,
        File(context.filesDir, "recordings"),
        rtspPort,
        recordingEnabled
    )
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val stopping = AtomicBoolean(false)
    private val lock = Any()
    private var process: Process? = null
    private var monitor: ScheduledFuture<*>? = null
    private var restartCount = 0
    private var lastCameras: List<Camera> = emptyList()

    fun start(cameras: List<Camera>) = synchronized(lock) {
        check(binary.isFile) { "APK thiếu MediaMTX ARM64 engine" }
        check(binary.canExecute()) { "MediaMTX engine không có quyền thực thi" }
        stopping.set(false)
        lastCameras = cameras
        configWriter.writeAtomically(configFile, cameras)
        spawnLocked()
    }

    fun reconfigure(cameras: List<Camera>) = synchronized(lock) {
        lastCameras = cameras
        configWriter.writeAtomically(configFile, cameras)
        if (process?.isAlive != true && !stopping.get()) {
            restartCount = 0
            spawnLocked()
        }
    }

    fun isRunning(): Boolean = synchronized(lock) { process?.isAlive == true }

    private fun spawnLocked() {
        runtimeDirectory.mkdirs()
        logFile.parentFile?.mkdirs()
        process = ProcessBuilder(binary.absolutePath, configFile.absolutePath)
            .directory(runtimeDirectory)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
        runtimeState.mediaMtxRunning(restartCount)
        monitor?.cancel(false)
        monitor = executor.schedule({ monitorProcess() }, 1, TimeUnit.SECONDS)
    }

    private fun monitorProcess() {
        val current = synchronized(lock) { process } ?: return
        if (current.isAlive) {
            monitor = executor.schedule({ monitorProcess() }, 2, TimeUnit.SECONDS)
            return
        }
        if (stopping.get()) return
        val exitCode = runCatching { current.exitValue() }.getOrDefault(-1)
        val delaySeconds = RESTART_DELAYS[minOf(restartCount, RESTART_DELAYS.lastIndex)]
        restartCount += 1
        runtimeState.mediaMtxFailed("MediaMTX dừng với mã $exitCode; thử lại sau ${delaySeconds}s", restartCount)
        monitor = executor.schedule({
            synchronized(lock) {
                if (!stopping.get()) runCatching { spawnLocked() }
                    .onFailure { runtimeState.mediaMtxFailed(it.message ?: "Không thể chạy MediaMTX", restartCount) }
            }
        }, delaySeconds.toLong(), TimeUnit.SECONDS)
    }

    override fun close() = synchronized(lock) {
        stopping.set(true)
        monitor?.cancel(true)
        monitor = null
        process?.takeIf(Process::isAlive)?.let { running ->
            running.destroy()
            if (!runCatching { running.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)) {
                running.destroyForcibly()
            }
        }
        process = null
        executor.shutdownNow()
        Unit
    }

    private companion object {
        val RESTART_DELAYS = intArrayOf(5, 10, 30, 60)
    }
}
