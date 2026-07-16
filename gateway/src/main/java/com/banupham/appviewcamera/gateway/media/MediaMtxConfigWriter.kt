package com.banupham.appviewcamera.gateway.media

import com.banupham.appviewcamera.gateway.camera.Camera
import com.banupham.appviewcamera.gateway.rtsp.RtspUrlFactory
import java.io.File
import org.json.JSONObject

class MediaMtxConfigWriter(
    private val passwordFor: (Camera) -> String,
    private val recordingRoot: File,
    private val rtspPort: Int,
    private val recordingEnabled: () -> Boolean = { true }
) {
    fun render(cameras: List<Camera>): String {
        val paths = JSONObject()
        cameras.filter(Camera::enabled).forEach { camera ->
            val source = RtspUrlFactory.withCredentials(
                camera.mainRtspUrl,
                camera.username,
                passwordFor(camera)
            )
            val primary = JSONObject()
                .put("source", source)
                .put("rtspTransport", "automatic")
                .put("sourceOnDemand", !(camera.recordEnabled && recordingEnabled()))
            if (camera.recordEnabled && recordingEnabled()) {
                val recordPath = File(
                    recordingRoot,
                    "${camera.relayPath}/%path/%Y-%m-%d/%Y-%m-%d_%H-%M-%S-%f"
                ).absolutePath
                primary
                    .put("record", true)
                    .put("recordPath", recordPath)
                    .put("recordFormat", "fmp4")
                    .put("recordPartDuration", "1s")
                    .put("recordSegmentDuration", "60s")
                    .put("recordDeleteAfter", "0s")
            }
            paths.put(camera.relayPath, primary)
            if (camera.subRtspUrl.isNotBlank()) {
                val subSource = RtspUrlFactory.withCredentials(
                    camera.subRtspUrl,
                    camera.username,
                    passwordFor(camera)
                )
                paths.put(
                    "${camera.relayPath}_sub",
                    JSONObject()
                        .put("source", subSource)
                        .put("rtspTransport", "automatic")
                        .put("sourceOnDemand", true)
                )
            }
        }
        return JSONObject()
            .put("logLevel", "warn")
            .put("rtsp", true)
            .put("rtspAddress", ":$rtspPort")
            .put("rtmp", false)
            .put("hls", false)
            .put("webrtc", false)
            .put("srt", false)
            .put("api", true)
            .put("apiAddress", "127.0.0.1:9997")
            .put("paths", paths)
            .toString(2)
    }

    fun writeAtomically(target: File, cameras: List<Camera>) {
        target.parentFile?.mkdirs()
        recordingRoot.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(render(cameras))
        check(temporary.renameTo(target) || run {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }) { "Không thể cập nhật MediaMTX config" }
    }
}
