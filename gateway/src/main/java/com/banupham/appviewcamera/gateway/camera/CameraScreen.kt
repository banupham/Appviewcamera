package com.banupham.appviewcamera.gateway.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CameraScreen(viewModel: CameraViewModel) {
    val cameras by viewModel.cameras.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val errors by viewModel.validationErrors.collectAsStateWithLifecycle()
    val testingIds by viewModel.testingCameraIds.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var deletingCamera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Camera Gateway", style = MaterialTheme.typography.headlineMedium)
                        Text("Camera đã cấu hình: ${cameras.size}")
                    }
                    Button(onClick = viewModel::beginAdd) { Text("Thêm camera") }
                }
            }
            if (cameras.isEmpty()) {
                item { Text("Chưa có camera. Thêm camera để kiểm tra luồng RTSP trong LAN.") }
            }
            items(cameras, key = Camera::id) { camera ->
                CameraCard(
                    camera = camera,
                    isTesting = camera.id in testingIds,
                    onEdit = { viewModel.beginEdit(camera) },
                    onTest = { viewModel.test(camera) },
                    onDelete = { deletingCamera = camera }
                )
            }
        }
    }

    draft?.let { currentDraft ->
        CameraEditorDialog(
            draft = currentDraft,
            errors = errors,
            onUpdate = viewModel::updateDraft,
            onSave = viewModel::save,
            onDismiss = viewModel::dismissEditor
        )
    }

    deletingCamera?.let { camera ->
        AlertDialog(
            onDismissRequest = { deletingCamera = null },
            title = { Text("Xóa camera?") },
            text = { Text("Xóa ${camera.name} khỏi Gateway. Thao tác này không xóa dữ liệu trên camera.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.delete(camera)
                    deletingCamera = null
                }) { Text("Xóa") }
            },
            dismissButton = { TextButton(onClick = { deletingCamera = null }) { Text("Hủy") } }
        )
    }
}

@Composable
private fun CameraCard(
    camera: Camera,
    isTesting: Boolean,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(camera.name, style = MaterialTheme.typography.titleLarge)
                Text(camera.connectionStatus.displayName())
            }
            Text("${camera.ip}:${camera.port} • relay /${camera.relayPath}")
            Text("Main: ${camera.mainRtspUrl}")
            if (camera.subRtspUrl.isNotBlank()) Text("Sub: ${camera.subRtspUrl}")
            HorizontalDivider()
            val resolution = if (camera.width != null && camera.height != null) "${camera.width}×${camera.height}" else "—"
            val fps = camera.fps?.let { "%.2f".format(it) } ?: "—"
            val bitrate = camera.bitrate?.let { "%.2f Mbps".format(it / 1_000_000.0) } ?: "—"
            Text("Codec: ${camera.codec ?: "—"} • Độ phân giải: $resolution")
            Text("FPS: $fps • Bitrate: $bitrate")
            camera.lastError?.let { Text("Lỗi: $it", color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTest, enabled = !isTesting && camera.enabled) {
                    Text(if (isTesting) "Đang kiểm tra…" else "Test RTSP")
                }
                TextButton(onClick = onEdit, enabled = !isTesting) { Text("Sửa") }
                TextButton(onClick = onDelete, enabled = !isTesting) { Text("Xóa") }
            }
        }
    }
}

@Composable
private fun CameraEditorDialog(
    draft: CameraDraft,
    errors: List<String>,
    onUpdate: ((CameraDraft) -> CameraDraft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == 0L) "Thêm camera" else "Sửa camera") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(draft.name, { value -> onUpdate { it.copy(name = value) } }, label = { Text("Tên") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(draft.ip, { value -> onUpdate { it.copy(ip = value) } }, label = { Text("IP hoặc hostname") }, modifier = Modifier.fillMaxWidth()) }
                item {
                    OutlinedTextField(
                        draft.port,
                        { value -> onUpdate { it.copy(port = value.filter { character -> character.isDigit() }) } },
                        label = { Text("Cổng RTSP") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item { OutlinedTextField(draft.username, { value -> onUpdate { it.copy(username = value) } }, label = { Text("Tài khoản") }, modifier = Modifier.fillMaxWidth()) }
                item {
                    OutlinedTextField(
                        draft.password,
                        { value -> onUpdate { it.copy(password = value) } },
                        label = { Text(if (draft.id == 0L) "Mật khẩu" else "Mật khẩu mới (để trống nếu giữ nguyên)") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item { OutlinedTextField(draft.mainRtspUrl, { value -> onUpdate { it.copy(mainRtspUrl = value) } }, label = { Text("Main RTSP URL") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(draft.subRtspUrl, { value -> onUpdate { it.copy(subRtspUrl = value) } }, label = { Text("Sub RTSP URL (không bắt buộc)") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(draft.relayPath, { value -> onUpdate { it.copy(relayPath = value) } }, label = { Text("Relay path") }, modifier = Modifier.fillMaxWidth()) }
                item { CameraOption("Bật camera", draft.enabled) { value -> onUpdate { it.copy(enabled = value) } } }
                item { CameraOption("Cho phép ghi hình", draft.recordEnabled) { value -> onUpdate { it.copy(recordEnabled = value) } } }
                item { CameraOption("Phát hiện chuyển động", draft.motionEnabled) { value -> onUpdate { it.copy(motionEnabled = value) } } }
                item { CameraOption("Âm thanh", draft.audioEnabled) { value -> onUpdate { it.copy(audioEnabled = value) } } }
                items(errors) { error -> Text(error, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = onSave) { Text("Lưu") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}

@Composable
private fun CameraOption(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Checkbox(checked = checked, onCheckedChange = onChecked)
    }
}

private fun CameraConnectionStatus.displayName(): String = when (this) {
    CameraConnectionStatus.OFFLINE -> "Offline"
    CameraConnectionStatus.CONNECTING -> "Connecting"
    CameraConnectionStatus.CONNECTED -> "Connected"
    CameraConnectionStatus.RECONNECTING -> "Reconnecting"
}
