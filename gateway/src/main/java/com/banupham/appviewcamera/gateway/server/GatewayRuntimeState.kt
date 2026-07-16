package com.banupham.appviewcamera.gateway.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GatewayRuntimeSnapshot(
    val running: Boolean = false,
    val apiState: String = "STOPPED",
    val rtspState: String = "STOPPED",
    val activeRtspClients: Int = 0,
    val startedAt: Long? = null,
    val lastError: String? = null
)

class GatewayRuntimeState {
    private val _snapshot = MutableStateFlow(GatewayRuntimeSnapshot())
    val snapshot = _snapshot.asStateFlow()

    fun started() {
        _snapshot.value = GatewayRuntimeSnapshot(
            running = true,
            apiState = "RUNNING",
            rtspState = "RUNNING",
            startedAt = System.currentTimeMillis()
        )
    }

    fun failed(message: String) {
        _snapshot.value = _snapshot.value.copy(
            running = false,
            apiState = "ERROR",
            rtspState = "ERROR",
            lastError = message
        )
    }

    fun stopped() {
        _snapshot.value = GatewayRuntimeSnapshot()
    }

    fun clientConnected() {
        _snapshot.value = _snapshot.value.copy(activeRtspClients = _snapshot.value.activeRtspClients + 1)
    }

    fun clientDisconnected() {
        _snapshot.value = _snapshot.value.copy(activeRtspClients = (_snapshot.value.activeRtspClients - 1).coerceAtLeast(0))
    }
}
