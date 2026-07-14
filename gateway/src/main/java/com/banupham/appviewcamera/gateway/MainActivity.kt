package com.banupham.appviewcamera.gateway

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                GatewayScreen(GatewayState())
            }
        }
    }
}

@Composable
private fun GatewayScreen(state: GatewayState) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Camera Gateway", style = MaterialTheme.typography.headlineMedium)
            Text("Thiết bị trung gian riêng tư giữa camera LAN và Viewer qua Tailscale.")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Trạng thái hệ thống", style = MaterialTheme.typography.titleMedium)
                    Text("Dịch vụ: ${state.serviceState.displayName}")
                    Text("Camera đã cấu hình: ${state.configuredCameraCount}")
                    Text("Camera đang ghi: ${state.recordingCameraCount}")
                }
            }
            Text("Giai đoạn 1 chỉ khởi tạo ứng dụng. Chưa có RTSP relay hoặc ghi hình.")
        }
    }
}
