package com.banupham.appviewcamera.viewer.live

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.banupham.appviewcamera.viewer.ViewerUiState
import com.banupham.appviewcamera.viewer.api.CameraSummary
import com.banupham.appviewcamera.viewer.player.RtspPlayer

@Composable
fun MultiCameraLiveScreen(state: ViewerUiState, onSelectCamera: (String) -> Unit) {
    if (state.config.validate().isFailure) {
        LivePlaceholder("Mở Cài đặt để nhập IP Gateway và API token.")
        return
    }
    if (state.gatewayConnectionError != null || state.gatewayStatus == null) {
        LivePlaceholder("Không kết nối được Gateway. Hãy kiểm tra Wi-Fi/Tailscale rồi thử lại.")
        return
    }
    val enabledCameras = state.cameras.filter { it.enabled }
    if (enabledCameras.isEmpty()) {
        LivePlaceholder("Chưa có camera. Mở Thiết bị để quét LAN hoặc thêm camera.")
        return
    }

    val context = LocalContext.current
    val preferences = remember(context) { LiveViewPreferences(context.applicationContext) }
    val decoderCapacity = remember(context) { detectDecoderCapacity(context) }
    var layout by remember { mutableStateOf(preferences.loadLayout()) }
    var selectedIds by remember {
        mutableStateOf(
            preferences.loadCameraIds().ifEmpty {
                listOfNotNull(state.selectedCameraId ?: enabledCameras.firstOrNull()?.id)
            }
        )
    }
    var expandedCameraId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(enabledCameras.map { it.id }, layout) {
        selectedIds = reconcileCameraSelection(
            enabledCameras.map { it.id }, selectedIds, layout.slots
        )
        preferences.save(layout, selectedIds)
    }

    val plan = buildLiveGridPlan(state.cameras, selectedIds, layout, decoderCapacity)
    val expanded = plan.cameras.firstOrNull { it.id == expandedCameraId }
    LaunchedEffect(expandedCameraId, plan.cameras.map { it.id }) {
        if (expandedCameraId != null && expanded == null) expandedCameraId = null
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (expanded != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(expanded.name, style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { expandedCameraId = null }) { Text("Trở về lưới") }
            }
            RtspPlayer(
                url = state.config.relayUrl(liveRelayPath(expanded, expanded = true)),
                modifier = Modifier.fillMaxWidth().weight(1f),
                showControls = true
            )
            return@Column
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowItems(LiveLayout.entries.filter { it.selectable }, key = { it.slots }) { option ->
                FilterChip(
                    selected = layout == option,
                    onClick = {
                        layout = option
                        selectedIds = reconcileCameraSelection(
                            enabledCameras.map { it.id }, selectedIds, option.slots
                        )
                        preferences.save(option, selectedIds)
                    },
                    label = { Text("${option.slots} camera") }
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowItems(enabledCameras, key = { it.id }) { camera ->
                FilterChip(
                    selected = camera.id in selectedIds,
                    onClick = {
                        selectedIds = updateSelectedCameras(selectedIds, camera.id, layout.slots)
                        preferences.save(layout, selectedIds)
                        onSelectCamera(camera.id)
                    },
                    label = { Text(camera.name) }
                )
            }
        }
        if (plan.decoderLimited) {
            Text(
                "Thiết bị chỉ mở an toàn $decoderCapacity/${layout.slots} luồng cùng lúc.",
                color = MaterialTheme.colorScheme.error
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(layout.columns),
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            gridItems(plan.cameras, key = { it.id }) { camera ->
                LiveCameraTile(
                    camera = camera,
                    url = state.config.relayUrl(liveRelayPath(camera, expanded = false)),
                    onExpand = {
                        onSelectCamera(camera.id)
                        expandedCameraId = camera.id
                    }
                )
            }
        }
    }
}

@Composable
private fun LiveCameraTile(camera: CameraSummary, url: String, onExpand: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        Box(modifier = Modifier.fillMaxSize()) {
            RtspPlayer(url = url, modifier = Modifier.fillMaxSize(), showControls = false)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(camera.name, color = Color.White, maxLines = 1)
                TextButton(onClick = onExpand) { Text("Phóng to", color = Color.White) }
            }
        }
    }
}

@Composable
private fun LivePlaceholder(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text) }
}

internal fun updateSelectedCameras(current: List<String>, cameraId: String, slots: Int): List<String> {
    if (slots <= 1) return listOf(cameraId)
    if (cameraId in current) return if (current.size > 1) current - cameraId else current
    return if (current.size < slots) current + cameraId else current.drop(1) + cameraId
}

internal fun detectDecoderCapacity(context: Context): Int = runCatching {
    val reported = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        .asSequence()
        .filterNot { it.isEncoder }
        .filter { codec ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) codec.isHardwareAccelerated
            else !codec.name.startsWith("OMX.google", true) && !codec.name.startsWith("c2.android", true)
        }
        .flatMap { codec ->
            codec.supportedTypes.asSequence()
                .filter { it.equals("video/avc", true) || it.equals("video/hevc", true) }
                .mapNotNull { type -> runCatching { codec.getCapabilitiesForType(type).maxSupportedInstances }.getOrNull() }
        }
        .maxOrNull()
    val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    minOf(reported ?: if (lowRam) 1 else 2, if (lowRam) 2 else 4).coerceAtLeast(1)
}.getOrDefault(1)
