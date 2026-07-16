package com.banupham.appviewcamera.gateway.server

import com.banupham.appviewcamera.gateway.camera.Camera
import com.banupham.appviewcamera.gateway.camera.CameraDraft
import com.banupham.appviewcamera.gateway.camera.CameraRepository
import com.banupham.appviewcamera.gateway.camera.CameraValidator
import com.banupham.appviewcamera.gateway.recording.PlaybackIndex
import com.banupham.appviewcamera.gateway.recording.RecordingRepository
import com.banupham.appviewcamera.gateway.recording.RecordingSettingsStore
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class GatewayHttpServer(
    private val settings: GatewaySettings,
    private val repository: CameraRepository,
    private val runtimeState: GatewayRuntimeState,
    private val recordings: RecordingRepository,
    private val recordingSettings: RecordingSettingsStore,
    private val onRecordingSettingsChanged: suspend () -> Unit
) : Closeable {
    private val playback = PlaybackIndex(recordings)
    private val running = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(16)
    private var serverSocket: ServerSocket? = null

    fun start() {
        check(running.compareAndSet(false, true)) { "HTTP API đã chạy" }
        serverSocket = ServerSocket(settings.apiPort).apply { reuseAddress = true }
        executor.execute {
            while (running.get()) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    executor.execute { handle(socket) }
                } catch (_: Exception) {
                    if (running.get()) runtimeState.failed("HTTP API không nhận được kết nối")
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            it.soTimeout = 15_000
            val response = runCatching {
                val request = HttpRequest.readFrom(it.getInputStream()) ?: return
                runBlocking { route(request) }
            }.getOrElse { error ->
                HttpResponse.json(
                    if (error is IllegalArgumentException) 400 else 500,
                    JSONObject().put("detail", error.message ?: "Gateway xử lý thất bại")
                )
            }
            response.writeTo(it)
        }
    }

    private suspend fun route(request: HttpRequest): HttpResponse {
        val uri = URI(request.target)
        val path = uri.path
        if (request.method == "GET" && path == "/health") {
            return HttpResponse.json(200, JSONObject().put("status", "ok").put("version", "0.4.0"))
        }
        if (!authorized(request.headers["authorization"].orEmpty())) {
            return HttpResponse.json(401, JSONObject().put("detail", "Bearer token không hợp lệ"))
        }
        return when {
            request.method == "GET" && path == "/api/status" -> status()
            request.method == "GET" && path == "/api/cameras" -> cameras()
            request.method == "GET" && path == "/api/discovery/candidates" -> configuredCandidates()
            request.method == "POST" && path == "/api/discovery/scan" -> scanCandidates()
            path.startsWith("/api/cameras/") -> cameraMutation(request, path)
            request.method == "GET" && path == "/api/recording" -> recordingStatus()
            request.method == "PUT" && path == "/api/recording" -> updateRecording(request)
            request.method == "GET" && path == "/api/recordings" -> listRecordings(uri)
            path.startsWith("/api/recordings/") -> recordingMutation(request, path)
            request.method == "GET" && path == "/api/playback/days" -> playbackDays(uri)
            request.method == "GET" && path == "/api/playback/timeline" -> playbackTimeline(uri)
            path.startsWith("/api/playback/items/") -> playbackItem(request, uri, path)
            path.startsWith("/api/storage/") || path.startsWith("/api/youtube/") -> HttpResponse.json(
                    501,
                    JSONObject().put("detail", "Lớp lưu trữ đám mây Android Gateway đang được cấu hình")
                )
            else -> HttpResponse.json(404, JSONObject().put("detail", "Không tìm thấy endpoint"))
        }
    }

    private suspend fun status(): HttpResponse {
        val cameras = repository.list()
        val runtime = runtimeState.snapshot.value
        return HttpResponse.json(200, JSONObject().apply {
            put("status", if (runtime.running) "ONLINE" else "DEGRADED")
            put("gateway_id", settings.gatewayId)
            put("gateway_name", settings.gatewayName)
            put("version", "0.4.0")
            put("camera_count", cameras.size)
            put("candidate_count", cameras.size)
            put("mediamtx", JSONObject()
                .put("state", runtime.rtspState)
                .put("implementation", "embedded-mediamtx")
                .put("restart_count", runtime.mediaMtxRestartCount))
            put("capabilities", JSONObject()
                .put("camera_crud", true)
                .put("rtsp_relay", true)
                .put("discovery", true)
                .put("recording", true)
                .put("playback", true)
                .put("google_drive", false)
                .put("youtube", false))
        })
    }

    private suspend fun cameras(): HttpResponse {
        val array = JSONArray()
        repository.list().forEach { array.put(cameraJson(it)) }
        return HttpResponse.json(200, array)
    }

    private suspend fun recordingStatus(): HttpResponse {
        val config = recordingSettings.get()
        val status = recordings.status()
        return HttpResponse.json(200, JSONObject()
            .put("enabled", config.effectiveEnabled)
            .put("configured_enabled", config.enabled)
            .put("storage_paused", config.storagePaused)
            .put("local_retention_minutes", config.localRetentionMinutes)
            .put("storage_mode", "TIERED_LOCAL_CACHE")
            .put("local_cache_percent", 1)
            .put("clip_count", status.clipCount)
            .put("disk_free_bytes", status.diskFreeBytes)
            .put("disk_total_bytes", status.diskTotalBytes)
            .put("local_bytes", status.localBytes)
            .put("local_cache_budget_bytes", status.cacheBudgetBytes)
            .put("storage_pressure", status.storagePressure)
            .put("upload_counts", JSONObject()
                .put("PENDING", status.pendingUploads)
                .put("FAILED", status.failedUploads)
                .put("UPLOADED", status.uploadedClips))
            .put("drive_synced_at_ms", JSONObject.NULL)
            .put("youtube_batch_minutes", 0)
            .put("youtube_target_minutes", 360))
    }

    private suspend fun updateRecording(request: HttpRequest): HttpResponse {
        val json = JSONObject(request.body.toString(StandardCharsets.UTF_8))
        val current = recordingSettings.get()
        val updated = recordingSettings.update(
            enabled = if (json.has("enabled")) json.getBoolean("enabled") else current.enabled,
            localRetentionMinutes = if (json.has("local_retention_minutes")) json.getInt("local_retention_minutes") else null
        )
        onRecordingSettingsChanged()
        return recordingStatus().also { check(updated == recordingSettings.get()) }
    }

    private suspend fun listRecordings(uri: URI): HttpResponse {
        val query = queryParameters(uri)
        val clips = recordings.list(
            query["camera_id"], query["from_ms"]?.toLongOrNull(), query["to_ms"]?.toLongOrNull(),
            query["limit"]?.toIntOrNull() ?: 200
        )
        return HttpResponse.json(200, JSONObject().put("count", clips.size).put("clips", JSONArray().apply {
            clips.forEach { clip ->
                put(JSONObject().put("id", clip.id).put("camera_id", clip.cameraId)
                    .put("started_at_ms", clip.startedAtMs).put("duration_ms", clip.durationMs ?: JSONObject.NULL)
                    .put("size_bytes", clip.sizeBytes).put("local_state", clip.localState)
                    .put("upload_state", clip.uploadState).put("last_error", clip.lastError ?: JSONObject.NULL)
                    .put("protected", clip.isProtected).put("motion", clip.motion))
            }
        }))
    }

    private suspend fun recordingMutation(request: HttpRequest, path: String): HttpResponse {
        val suffix = path.removePrefix("/api/recordings/")
        return when {
            suffix.endsWith("/protection") && request.method == "PUT" -> {
                val id = decode(suffix.removeSuffix("/protection"))
                val isProtected = JSONObject(request.body.toString(StandardCharsets.UTF_8)).getBoolean("protected")
                val clip = recordings.setProtected(id, isProtected)
                    ?: return HttpResponse.json(404, JSONObject().put("detail", "Không tìm thấy clip"))
                HttpResponse.json(200, playback.itemJson(clip))
            }
            suffix.endsWith("/content") && request.method in setOf("GET", "HEAD") -> {
                val clip = recordings.get(decode(suffix.removeSuffix("/content")))
                    ?: return HttpResponse.json(404, JSONObject().put("detail", "Không tìm thấy clip"))
                val file = recordings.localFile(clip)
                    ?: return HttpResponse.json(404, JSONObject().put("detail", "Clip không còn trong cache local"))
                HttpResponse.file(file, request.headers["range"], request.method == "HEAD")
            }
            else -> HttpResponse.json(405, JSONObject().put("detail", "Method không được hỗ trợ"))
        }
    }

    private suspend fun playbackDays(uri: URI): HttpResponse {
        val query = queryParameters(uri)
        return HttpResponse.json(200, playback.days(query["camera_id"].orEmpty(), query["limit"]?.toIntOrNull() ?: 90))
    }

    private suspend fun playbackTimeline(uri: URI): HttpResponse {
        val query = queryParameters(uri)
        return HttpResponse.json(200, playback.timeline(
            cameraId = query["camera_id"].orEmpty(),
            fromMs = query["from_ms"]?.toLongOrNull(),
            toMs = query["to_ms"]?.toLongOrNull(),
            day = query["day"],
            limit = query["limit"]?.toIntOrNull() ?: 500
        ))
    }

    private suspend fun playbackItem(request: HttpRequest, uri: URI, path: String): HttpResponse {
        if (request.method !in setOf("GET", "HEAD")) {
            return HttpResponse.json(405, JSONObject().put("detail", "Method không được hỗ trợ"))
        }
        val suffix = path.removePrefix("/api/playback/items/")
        return when {
            suffix.endsWith("/sources") -> playback.sources(decode(suffix.removeSuffix("/sources")))
                ?.let { HttpResponse.json(200, it) }
                ?: HttpResponse.json(404, JSONObject().put("detail", "Không tìm thấy playback item"))
            suffix.endsWith("/stream") -> {
                val id = decode(suffix.removeSuffix("/stream"))
                val clip = recordings.get(id)
                    ?: return HttpResponse.json(404, JSONObject().put("detail", "Không tìm thấy playback item"))
                val source = queryParameters(uri)["source"]?.lowercase() ?: "auto"
                require(source in setOf("auto", "local", "drive")) { "source phải là auto, local hoặc drive" }
                if (source == "drive") return HttpResponse.json(409, JSONObject()
                    .put("detail", "Bản Drive chưa được tải vào playback cache")
                    .put("sources_url", "/api/playback/items/$id/sources"))
                val file = recordings.localFile(clip)
                    ?: return HttpResponse.json(409, JSONObject().put("detail", "Nguồn local chưa sẵn sàng"))
                HttpResponse.file(file, request.headers["range"], request.method == "HEAD")
            }
            else -> playback.item(decode(suffix))?.let { HttpResponse.json(200, it) }
                ?: HttpResponse.json(404, JSONObject().put("detail", "Không tìm thấy playback item"))
        }
    }

    private fun queryParameters(uri: URI): Map<String, String> = uri.rawQuery.orEmpty()
        .split('&').filter(String::isNotBlank).associate { part ->
            val pieces = part.split('=', limit = 2)
            decode(pieces[0]) to decode(pieces.getOrElse(1) { "" })
        }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private suspend fun configuredCandidates(): HttpResponse {
        val array = JSONArray()
        repository.list().forEach { camera ->
            array.put(JSONObject().put("host", camera.ip).put("port", camera.port).put("source", "configured"))
        }
        return HttpResponse.json(200, array)
    }

    private suspend fun scanCandidates(): HttpResponse {
        val configured = repository.list().map { DiscoveryCandidate(it.ip, it.port, "configured") }
        val discovered = LanCameraDiscovery.scan(LanAddressResolver.resolve())
        val unique = (configured + discovered).distinctBy { it.host to it.port }
        val array = JSONArray()
        unique.forEach { array.put(JSONObject().put("host", it.host).put("port", it.port).put("source", it.source)) }
        return HttpResponse.json(200, JSONObject().put("count", unique.size).put("candidates", array))
    }

    private suspend fun cameraMutation(request: HttpRequest, path: String): HttpResponse {
        val cameraId = URLDecoder.decode(path.removePrefix("/api/cameras/"), StandardCharsets.UTF_8.name())
        if (!cameraId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            return HttpResponse.json(400, JSONObject().put("detail", "camera_id không hợp lệ"))
        }
        return when (request.method) {
            "PUT" -> saveCamera(cameraId, request.body)
            "DELETE" -> {
                val camera = repository.getByRelayPath(cameraId)
                    ?: return HttpResponse.json(404, JSONObject().put("detail", "Không tìm thấy camera"))
                repository.delete(camera.id)
                HttpResponse.json(200, JSONObject().put("deleted", true))
            }
            else -> HttpResponse.json(405, JSONObject().put("detail", "Method không được hỗ trợ"))
        }
    }

    private suspend fun saveCamera(cameraId: String, body: ByteArray): HttpResponse {
        val json = JSONObject(body.toString(StandardCharsets.UTF_8))
        if (json.optString("id") != cameraId) {
            return HttpResponse.json(400, JSONObject().put("detail", "camera_id không khớp nội dung"))
        }
        val relayPath = json.optString("relay_path", cameraId)
        if (relayPath != cameraId) {
            return HttpResponse.json(400, JSONObject().put("detail", "Android Gateway yêu cầu id trùng relay_path"))
        }
        val existing = repository.getByRelayPath(cameraId)
        val host = json.optString("host").trim()
        val port = json.optInt("port", 554)
        val draft = CameraDraft(
            id = existing?.id ?: 0,
            name = json.optString("name", cameraId),
            ip = host,
            port = port.toString(),
            username = json.optString("username"),
            password = if (json.has("password")) json.optString("password") else "",
            mainRtspUrl = rtspUrl(host, port, json.optString("main_path")),
            subRtspUrl = json.optString("sub_path").takeIf(String::isNotBlank)?.let { rtspUrl(host, port, it) }.orEmpty(),
            relayPath = relayPath,
            enabled = json.optBoolean("enabled", true),
            recordEnabled = json.optBoolean("record_enabled", false),
            motionEnabled = json.optBoolean("motion_enabled", false),
            audioEnabled = json.optBoolean("audio_enabled", true)
        )
        val errors = CameraValidator.validate(draft)
        if (errors.isNotEmpty()) return HttpResponse.json(400, JSONObject().put("detail", errors.joinToString("; ")))
        val id = repository.save(draft)
        val saved = repository.get(if (existing == null) id else existing.id) ?: error("Không đọc lại được camera")
        return HttpResponse.json(200, cameraJson(saved))
    }

    private fun cameraJson(camera: Camera): JSONObject = JSONObject().apply {
        put("id", camera.relayPath)
        put("name", camera.name)
        put("host", camera.ip)
        put("port", camera.port)
        put("username", camera.username)
        put("main_path", pathAndQuery(camera.mainRtspUrl))
        put("sub_path", camera.subRtspUrl.takeIf(String::isNotBlank)?.let(::pathAndQuery).orEmpty())
        put("relay_path", camera.relayPath)
        put("preview_relay_path", if (camera.subRtspUrl.isBlank()) camera.relayPath else "${camera.relayPath}_sub")
        put("enabled", camera.enabled)
        put("record_enabled", camera.recordEnabled)
        put("storage_enabled", camera.recordEnabled)
        put("motion_enabled", camera.motionEnabled)
        put("audio_enabled", camera.audioEnabled)
        put("connection_status", camera.connectionStatus.name)
        camera.lastError?.let { put("last_error", it) }
    }

    private fun rtspUrl(host: String, port: Int, path: String): String {
        if (path.startsWith("rtsp://", true)) return path
        val displayHost = if (host.contains(':')) "[$host]" else host
        return "rtsp://$displayHost:$port/${path.trimStart('/')}"
    }

    private fun pathAndQuery(url: String): String {
        val uri = URI(url)
        return uri.rawPath.orEmpty().trimStart('/') + uri.rawQuery?.let { "?$it" }.orEmpty()
    }

    private fun authorized(header: String): Boolean {
        val supplied = header.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ").orEmpty()
        return MessageDigest.isEqual(settings.apiToken.toByteArray(), supplied.toByteArray())
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket?.close() }
        executor.shutdownNow()
    }
}

data class DiscoveryCandidate(val host: String, val port: Int, val source: String)

object LanCameraDiscovery {
    private val ports = listOf(554, 80, 8000, 8554)

    fun scan(localAddress: String): List<DiscoveryCandidate> {
        val octets = localAddress.split('.')
        if (octets.size != 4 || octets.any { part -> part.toIntOrNull()?.let { it in 0..255 } != true }) return emptyList()
        val prefix = octets.take(3).joinToString(".")
        val pool = Executors.newFixedThreadPool(48)
        return try {
            val tasks = (1..254).flatMap { suffix ->
                ports.map { port ->
                    pool.submit<DiscoveryCandidate?> {
                        val host = "$prefix.$suffix"
                        if (host == localAddress) return@submit null
                        runCatching {
                            Socket().use { it.connect(InetSocketAddress(host, port), 220) }
                            DiscoveryCandidate(host, port, "tcp")
                        }.getOrNull()
                    }
                }
            }
            tasks.mapNotNull { runCatching { it.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }
}

data class HttpRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    companion object {
        fun readFrom(input: InputStream): HttpRequest? {
            val head = readHead(input) ?: return null
            val lines = head.split("\r\n")
            val requestLine = lines.first().split(' ', limit = 3)
            require(requestLine.size == 3) { "HTTP request line không hợp lệ" }
            val headers = lines.drop(1).filter(String::isNotBlank).associate { line ->
                val separator = line.indexOf(':')
                require(separator > 0) { "HTTP header không hợp lệ" }
                line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(body, offset, length - offset)
                if (count < 0) error("HTTP body bị ngắt")
                offset += count
            }
            return HttpRequest(requestLine[0].uppercase(), requestLine[1], headers, body)
        }

        private fun readHead(input: InputStream): String? {
            val output = ByteArrayOutputStream()
            var matched = 0
            while (output.size() < MAX_HEADER_BYTES) {
                val value = input.read()
                if (value < 0) return if (output.size() == 0) null else error("HTTP header bị ngắt")
                output.write(value)
                matched = when {
                    matched == 0 && value == '\r'.code -> 1
                    matched == 1 && value == '\n'.code -> 2
                    matched == 2 && value == '\r'.code -> 3
                    matched == 3 && value == '\n'.code -> return output.toString(StandardCharsets.ISO_8859_1.name()).dropLast(4)
                    value == '\r'.code -> 1
                    else -> 0
                }
            }
            error("HTTP header quá lớn")
        }

        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_BODY_BYTES = 1024 * 1024
    }
}

data class FileSlice(val file: File, val start: Long, val endInclusive: Long)

data class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray = byteArrayOf(),
    val headers: Map<String, String> = emptyMap(),
    val fileSlice: FileSlice? = null,
    val headOnly: Boolean = false
) {
    fun writeTo(socket: Socket) {
        val reason = when (status) {
            200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"
            416 -> "Range Not Satisfiable"; 500 -> "Internal Server Error"; 501 -> "Not Implemented"
            else -> "Error"
        }
        val output = socket.getOutputStream()
        val length = fileSlice?.let { it.endInclusive - it.start + 1 } ?: body.size.toLong()
        val extraHeaders = buildString { headers.forEach { (name, value) -> append("$name: $value\r\n") } }
        output.write((
            "HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\nContent-Length: $length\r\n" +
                "Connection: close\r\nCache-Control: no-store\r\n$extraHeaders\r\n"
            ).toByteArray(StandardCharsets.ISO_8859_1))
        if (!headOnly) {
            val slice = fileSlice
            if (slice == null) output.write(body) else RandomAccessFile(slice.file, "r").use { input ->
                input.seek(slice.start)
                var remaining = slice.endInclusive - slice.start + 1
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
        output.flush()
    }

    companion object {
        fun json(status: Int, value: Any): HttpResponse = HttpResponse(
            status,
            "application/json; charset=utf-8",
            value.toString().toByteArray(StandardCharsets.UTF_8)
        )

        fun file(file: File, rangeHeader: String?, headOnly: Boolean): HttpResponse {
            val size = file.length()
            if (size <= 0) return json(404, JSONObject().put("detail", "Clip rỗng"))
            val range = try {
                ByteRange.parse(rangeHeader, size)
            } catch (_: IllegalArgumentException) {
                return HttpResponse(
                    status = 416,
                    contentType = "application/json; charset=utf-8",
                    body = JSONObject().put("detail", "HTTP Range không hợp lệ").toString().toByteArray(),
                    headers = mapOf("Content-Range" to "bytes */$size"),
                    headOnly = headOnly
                )
            }
            return HttpResponse(
                status = if (rangeHeader.isNullOrBlank()) 200 else 206,
                contentType = "video/mp4",
                headers = buildMap {
                    put("Accept-Ranges", "bytes")
                    if (!rangeHeader.isNullOrBlank()) put("Content-Range", "bytes ${range.first}-${range.last}/$size")
                },
                fileSlice = FileSlice(file, range.first, range.last),
                headOnly = headOnly
            )
        }
    }
}

object ByteRange {
    fun parse(header: String?, size: Long): LongRange {
        require(size > 0)
        if (header.isNullOrBlank()) return 0L..(size - 1)
        require(header.startsWith("bytes=") && !header.contains(','))
        val value = header.removePrefix("bytes=")
        val parts = value.split('-', limit = 2)
        require(parts.size == 2)
        if (parts[0].isBlank()) {
            val suffixLength = parts[1].toLongOrNull() ?: throw IllegalArgumentException()
            require(suffixLength > 0)
            return (size - suffixLength.coerceAtMost(size))..(size - 1)
        }
        val start = parts[0].toLongOrNull() ?: throw IllegalArgumentException()
        val end = if (parts[1].isBlank()) size - 1 else parts[1].toLongOrNull() ?: throw IllegalArgumentException()
        require(start in 0 until size && end >= start)
        return start..end.coerceAtMost(size - 1)
    }
}
