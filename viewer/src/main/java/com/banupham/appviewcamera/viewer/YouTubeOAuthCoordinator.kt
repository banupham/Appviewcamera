package com.banupham.appviewcamera.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.banupham.appviewcamera.viewer.api.HttpGatewayApi
import com.banupham.appviewcamera.viewer.api.OAuthProxyResponse
import com.banupham.appviewcamera.viewer.api.YouTubeOAuthSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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

class YouTubeOAuthCoordinator(
    private val context: Context,
    private val api: HttpGatewayApi
) {
    suspend fun authorize(accountId: String, displayName: String, reconnect: Boolean = false): YouTubeOAuthSession = coroutineScope {
        val session = if (reconnect) api.reconnectYouTube(accountId)
        else api.startYouTubeOAuth(accountId, displayName)
        val url = session.authorizationUrl
            ?: error(session.error ?: "Gateway không tạo được trang đăng nhập YouTube")
        val server = withContext(Dispatchers.IO) { tryBind() }
            ?: error("Không mở được cổng OAuth 53683 trên Viewer")
        val proxyJob = launch(Dispatchers.IO) { serve(server, session.sessionId) }
        try {
            withContext(Dispatchers.Main) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            repeat(300) {
                delay(1_000)
                val current = api.youtubeOAuthStatus(session.sessionId)
                when (current.status) {
                    "COMPLETE" -> return@coroutineScope current
                    "ERROR" -> error(current.error ?: "Đăng nhập YouTube thất bại")
                }
            }
            error("Đăng nhập YouTube quá thời gian 5 phút")
        } finally {
            server.close()
            proxyJob.cancelAndJoin()
        }
    }

    private fun tryBind(): ServerSocket? = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("localhost"), CALLBACK_PORT), 4)
            soTimeout = 1_000
        }
    } catch (_: Exception) { null }

    private suspend fun serve(server: ServerSocket, sessionId: String) {
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
        val parts = reader.readLine().orEmpty().split(' ')
        require(parts.size >= 2 && parts[0] == "GET") { "OAuth callback không hợp lệ" }
        while (true) if (reader.readLine().isNullOrEmpty()) break
        writeResponse(socket, api.forwardYouTubeOAuthCallback(sessionId, parts[1]))
    }

    private fun writeResponse(socket: Socket, response: OAuthProxyResponse) {
        socket.getOutputStream().apply {
            write("HTTP/1.1 ${response.status} OAuth Response\r\n".toByteArray())
            response.headers.forEach { (name, value) -> write("$name: $value\r\n".toByteArray()) }
            write("Content-Length: ${response.body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(response.body)
            flush()
        }
    }

    private fun writeFailure(socket: Socket, message: String) {
        runCatching {
            val body = "Không hoàn tất được đăng nhập YouTube: $message".toByteArray()
            socket.getOutputStream().apply {
                write("HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain; charset=utf-8\r\n".toByteArray())
                write("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(body)
                flush()
            }
        }
    }

    private companion object { const val CALLBACK_PORT = 53683 }
}
