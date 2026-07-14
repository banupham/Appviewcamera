package com.banupham.appviewcamera.gateway

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.activity.viewModels
import com.banupham.appviewcamera.gateway.camera.CameraScreen
import com.banupham.appviewcamera.gateway.camera.CameraViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: CameraViewModel by viewModels {
        CameraViewModel.factory((application as GatewayApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CameraScreen(viewModel)
            }
        }
    }
}
