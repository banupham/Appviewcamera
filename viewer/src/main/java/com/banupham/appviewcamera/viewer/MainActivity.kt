package com.banupham.appviewcamera.viewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ViewerScreen() } }
    }
}

@Composable
private fun ViewerScreen() {
    var selectedSection by remember { mutableStateOf(ViewerSection.LIVE) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                ViewerSection.entries.forEach { section ->
                    NavigationBarItem(
                        selected = selectedSection == section,
                        onClick = { selectedSection = section },
                        icon = { Text(section.label.take(1)) },
                        label = { Text(section.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Camera Viewer", style = MaterialTheme.typography.headlineMedium)
            Text(selectedSection.label, style = MaterialTheme.typography.titleLarge)
            Text("Chưa ghép nối Gateway. Địa chỉ Tailscale sẽ do người dùng cấu hình.")
            Text("Giai đoạn 1 chưa phát luồng RTSP hoặc truy cập Google Drive.")
        }
    }
}
