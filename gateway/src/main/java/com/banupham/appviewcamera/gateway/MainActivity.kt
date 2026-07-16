package com.banupham.appviewcamera.gateway

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.activity.viewModels
import com.banupham.appviewcamera.gateway.camera.CameraScreen
import com.banupham.appviewcamera.gateway.camera.CameraViewModel
import com.banupham.appviewcamera.gateway.server.GatewayServerService
import com.banupham.appviewcamera.gateway.server.GatewayViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: CameraViewModel by viewModels {
        CameraViewModel.factory((application as GatewayApplication).container)
    }
    private val gatewayViewModel: GatewayViewModel by viewModels {
        GatewayViewModel.factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        val settings = (application as GatewayApplication).container.gatewaySettings.settings.value
        if (settings.autoStart) runCatching { GatewayServerService.start(this) }
        setContent {
            MaterialTheme {
                CameraScreen(viewModel, gatewayViewModel)
            }
        }
    }
}
