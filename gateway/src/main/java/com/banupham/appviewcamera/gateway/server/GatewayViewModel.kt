package com.banupham.appviewcamera.gateway.server

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.banupham.appviewcamera.gateway.GatewayApplication
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class GatewayUiState(
    val settings: GatewaySettings,
    val runtime: GatewayRuntimeSnapshot,
    val host: String,
    val availableHosts: List<String>
) {
    val pairingUri: String get() = settings.pairingUri(host)
}

class GatewayViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as GatewayApplication).container
    private val hosts = LanAddressResolver.resolveAll()

    private fun host(settings: GatewaySettings): String = settings.pairingHost.takeIf { it in hosts } ?: hosts.first()

    val state: StateFlow<GatewayUiState> = combine(
        container.gatewaySettings.settings,
        container.gatewayRuntimeState.snapshot
    ) { settings, runtime -> GatewayUiState(settings, runtime, host(settings), hosts) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            GatewayUiState(
                container.gatewaySettings.settings.value,
                container.gatewayRuntimeState.snapshot.value,
                host(container.gatewaySettings.settings.value),
                hosts
            )
        )

    fun start() = GatewayServerService.start(getApplication())

    fun stop() = GatewayServerService.stop(getApplication())

    fun setAutoStart(enabled: Boolean) = container.gatewaySettings.setAutoStart(enabled)

    fun setPairingHost(host: String) = container.gatewaySettings.setPairingHost(host)

    fun rotateToken() {
        container.gatewaySettings.rotateToken()
        if (container.gatewayRuntimeState.snapshot.value.running) GatewayServerService.restart(getApplication())
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(GatewayViewModel::class.java))
                return GatewayViewModel(application) as T
            }
        }
    }
}
