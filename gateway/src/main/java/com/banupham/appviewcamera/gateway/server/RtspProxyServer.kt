package com.banupham.appviewcamera.gateway.server

import com.banupham.appviewcamera.gateway.camera.Camera
import com.banupham.appviewcamera.gateway.camera.CameraRepository
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

class RtspProxyServer(
    private val port: Int,
    private val repository: CameraRepository,
    private val runtimeState: GatewayRuntimeState
) : Closeable {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private var serverSocket: ServerSocket? = null

    fun start() {
        check(running.compareAndSet(false, true)) { "RTSP proxy đã chạy" }
        serverSocket = ServerSocket(port).apply { reuseAddress = true }
        executor.execute {
            while (running.get()) {
                try {
                    val client = serverSocket?.accept() ?: break
                    if (clients.size >= MAX_CLIENTS) {
                        client.close()
                    } else {
                        clients += client
                        executor.execute { handleClient(client) }
                    }
                } catch (_: Exception) {
                    if (running.get()) runtimeState.failed("RTSP proxy không nhận được kết nối")
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        runtimeState.clientConnected()
        try {
            client.use { downstream ->
                downstream.soTimeout = CONTROL_TIMEOUT_MS
                runCatching {
                    val input = downstream.getInputStream()
                    val first = RtspPacket.readFrom(input) ?: return@runCatching
                    val target = requestTarget(first)
                    val relayPath = relayPath(target)
                    val camera = runBlocking { repository.getByRelayPath(relayPath) }?.takeIf(Camera::enabled)
                    if (camera == null) {
                        returnRtsp(downstream, first, 404, "Relay path not found")
                        return@runCatching
                    }
                    proxyCamera(downstream, first, target, camera)
                }.onFailure {
                    runCatching {
                        val output = downstream.getOutputStream()
                        RtspPacket("RTSP/1.0 502 Bad Gateway", listOf("Connection" to "close")).writeTo(output)
                    }
                }
            }
        } finally {
            clients -= client
            runtimeState.clientDisconnected()
        }
    }

    private fun proxyCamera(downstream: Socket, first: RtspPacket, downstreamBase: String, camera: Camera) {
        val upstreamUri = URI(camera.mainRtspUrl)
        val upstreamPort = if (upstreamUri.port > 0) upstreamUri.port else 554
        Socket().use { upstream ->
            upstream.connect(InetSocketAddress(upstreamUri.host, upstreamPort), CONNECT_TIMEOUT_MS)
            upstream.soTimeout = CONTROL_TIMEOUT_MS
            val upstreamInput = upstream.getInputStream()
            val upstreamOutput = upstream.getOutputStream()
            val downstreamInput = downstream.getInputStream()
            val downstreamOutput = downstream.getOutputStream()
            val password = repository.decryptPassword(camera)
            val authenticator = RtspAuthenticator(camera.username, password)
            var request: RtspPacket? = first
            while (request != null) {
                val method = request.startLine.substringBefore(' ').uppercase()
                val rewritten = rewriteRequest(request, downstreamBase, camera.mainRtspUrl)
                var response = exchange(rewritten, upstreamInput, upstreamOutput, authenticator)
                response = rewriteResponse(response, camera.mainRtspUrl, downstreamBase)
                response.writeTo(downstreamOutput)
                if (method == "PLAY" && response.startLine.contains(" 200 ")) {
                    downstream.soTimeout = 0
                    upstream.soTimeout = 0
                    executor.execute { runCatching { upstreamInput.copyTo(downstreamOutput) }; runCatching { downstream.close() } }
                    while (true) {
                        val next = RtspPacket.readFrom(downstreamInput) ?: break
                        rewriteRequest(next, downstreamBase, camera.mainRtspUrl)
                            .let { addAuthorization(it, authenticator) }
                            .writeTo(upstreamOutput)
                    }
                    return
                }
                request = RtspPacket.readFrom(downstreamInput)
            }
        }
    }

    private fun exchange(
        request: RtspPacket,
        input: java.io.InputStream,
        output: java.io.OutputStream,
        authenticator: RtspAuthenticator
    ): RtspPacket {
        var actual = addAuthorization(request, authenticator)
        actual.writeTo(output)
        var response = RtspPacket.readFrom(input) ?: error("Camera đóng RTSP connection")
        if (response.startLine.contains(" 401 ")) {
            val challenge = response.header("WWW-Authenticate")
            if (challenge != null && authenticator.acceptChallenge(challenge)) {
                actual = addAuthorization(request, authenticator)
                actual.writeTo(output)
                response = RtspPacket.readFrom(input) ?: error("Camera đóng RTSP connection")
            }
        }
        return response
    }

    private fun addAuthorization(packet: RtspPacket, authenticator: RtspAuthenticator): RtspPacket {
        val parts = packet.startLine.split(' ', limit = 3)
        val authorization = if (parts.size == 3) authenticator.authorization(parts[0], parts[1]) else null
        return packet.withoutHeader("Authorization").let {
            if (authorization == null) it else it.replacingHeader("Authorization", authorization)
        }
    }

    private fun rewriteRequest(packet: RtspPacket, downstreamBase: String, upstreamBase: String): RtspPacket {
        val target = requestTarget(packet)
        if (target == "*") return packet
        val normalizedDownstream = downstreamBase.trimEnd('/')
        val normalizedUpstream = upstreamBase.trimEnd('/')
        val rewritten = when {
            target.startsWith(normalizedDownstream) -> normalizedUpstream + target.removePrefix(normalizedDownstream)
            runCatching { URI(target).host }.getOrNull() == URI(upstreamBase).host -> target
            else -> normalizedUpstream
        }
        return packet.withTarget(rewritten).withoutHeader("Authorization").withoutHeader("Proxy-Authorization")
    }

    private fun rewriteResponse(packet: RtspPacket, upstreamBase: String, downstreamBase: String): RtspPacket {
        val replace: (String) -> String = { it.replace(upstreamBase.trimEnd('/'), downstreamBase.trimEnd('/')) }
        return packet.copy(
            headers = packet.headers.map { (name, value) -> name to replace(value) },
            body = replace(packet.body.toString(StandardCharsets.UTF_8)).toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun requestTarget(packet: RtspPacket): String = packet.startLine.split(' ', limit = 3)
        .getOrNull(1) ?: error("RTSP request line không hợp lệ")

    private fun relayPath(target: String): String {
        val rawPath = runCatching { URI(target).rawPath }.getOrNull().orEmpty()
        return URLDecoder.decode(rawPath.trim('/').substringBefore('/'), StandardCharsets.UTF_8.name())
            .takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
            ?: error("Relay path không hợp lệ")
    }

    private fun returnRtsp(socket: Socket, request: RtspPacket, status: Int, reason: String) {
        val headers = buildList {
            request.header("CSeq")?.let { add("CSeq" to it) }
            add("Connection" to "close")
        }
        RtspPacket("RTSP/1.0 $status $reason", headers).writeTo(socket.getOutputStream())
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket?.close() }
        clients.toList().forEach { runCatching { it.close() } }
        clients.clear()
        executor.shutdownNow()
    }

    private companion object {
        const val CONTROL_TIMEOUT_MS = 15_000
        const val CONNECT_TIMEOUT_MS = 10_000
        const val MAX_CLIENTS = 16
    }
}
