package com.banupham.appviewcamera.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.banupham.appviewcamera.viewer.api.DriveOAuthSession
import com.banupham.appviewcamera.viewer.api.HttpGatewayApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

class DriveOAuthCoordinator(
    private val context: Context,
    private val api: HttpGatewayApi
) {
    suspend fun authorize(remoteId: String, displayName: String): DriveOAuthSession = coroutineScope {
        val session = api.startDriveOAuth(remoteId, displayName)
        val authorizationUrl = session.authorizationUrl
            ?: error(session.error ?: "Gateway không tạo được trang đăng nhập Google")

        // Nếu Viewer và Gateway cùng điện thoại, rclone đã giữ cổng này và browser
        // gọi thẳng vào Gateway. Nếu khác điện thoại, Viewer làm proxy callback cục bộ.
        val callbackServer = withContext(Dispatchers.IO) { tryBindCallbackServer() }
        val proxyJob = callbackServer?.let { server ->
            launch(Dispatchers.IO) { serveCallbacks(server, session.sessionId) }
        }
        try {
            withContext(Dispatchers.Main) {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
            repeat(300) {
                delay(1_000)
                val current = api.driveOAuthStatus(session.sessionId)
                when (current.status) {
                    "COMPLETE" -> return@coroutineScope current
                    "ERROR" -> error(current.error ?: "Đăng nhập Google thất bại")
                }
            }
            error("Đăng nhập Google quá thời gian 5 phút")
        } finally {
            callbackServer?.close()
            proxyJob?.cancelAndJoin()
        }
    }

    private fun tryBindCallbackServer(): ServerSocket? = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("localhost"), CALLBACK_PORT), 4)
            soTimeout = 1_000
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun serveCallbacks(server: ServerSocket, sessionId: String) {
        while (currentCoroutineContext().isActive && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                return
            }
            runCatching { proxy(socket, sessionId) }
                .onFailure { writeFailure(socket, it.message ?: "OAuth callback thất bại") }
            runCatching { socket.close() }
        }
    }

    private suspend fun proxy(socket: Socket, sessionId: String) {
        socket.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
        val requestLine = reader.readLine().orEmpty()
        val parts = requestLine.split(' ')
        require(parts.size >= 2 && parts[0] == "GET") { "OAuth callback không hợp lệ" }
        val path = parts[1]
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }
        val response = api.forwardDriveOAuthCallback(sessionId, path)
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n".toByteArray())
        response.headers.forEach { (name, value) ->
            output.write("$name: $value\r\n".toByteArray())
        }
        output.write("Content-Length: ${response.body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(response.body)
        output.flush()
    }

    private fun writeFailure(socket: Socket, message: String) {
        runCatching {
            val body = "Không hoàn tất được đăng nhập Google: $message".toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write("HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain; charset=utf-8\r\n".toByteArray())
                write("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(body)
                flush()
            }
        }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        301 -> "Moved Permanently"
        302 -> "Found"
        303 -> "See Other"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        else -> "OAuth Response"
    }

    private companion object {
        const val CALLBACK_PORT = 53682
    }
}
