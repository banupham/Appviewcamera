package com.banupham.appviewcamera.viewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.banupham.appviewcamera.viewer.api.CameraMutation
import com.banupham.appviewcamera.viewer.api.CameraSummary
import com.banupham.appviewcamera.viewer.api.GoogleDriveMutation
import com.banupham.appviewcamera.viewer.player.RtspPlayer
import com.banupham.appviewcamera.viewer.player.PlaybackPlayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ViewerScreen() } }
    }
}

@Composable
private fun ViewerScreen(viewModel: ViewerViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(selectedSection.label, style = MaterialTheme.typography.headlineSmall)
                state.gatewayStatus?.let { Text("Gateway: ${it.status}") }
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (state.loading) CircularProgressIndicator()
            when (selectedSection) {
                ViewerSection.LIVE -> LiveScreen(state, viewModel::selectCamera)
                ViewerSection.PLAYBACK -> PlaybackScreen(
                    state = state,
                    onSelectCamera = viewModel::selectCamera,
                    onRefresh = viewModel::loadRecordings,
                    onChangeDay = viewModel::changePlaybackDay,
                    onToggleRecording = viewModel::setRecordingEnabled,
                    onSelectClip = viewModel::selectRecording
                )
                ViewerSection.DEVICES -> DevicesScreen(
                    state = state,
                    onRefresh = viewModel::refresh,
                    onScan = viewModel::scanCameras,
                    onSave = viewModel::saveCamera,
                    onDelete = viewModel::deleteCamera
                )
                ViewerSection.STORAGE -> StorageScreen(
                    state = state,
                    onAdd = viewModel::addDrive,
                    onRefresh = viewModel::refreshDrive,
                    onDelete = viewModel::deleteDrive
                )
                ViewerSection.SETTINGS -> SettingsScreen(state, viewModel::saveConfig, viewModel::refresh)
            }
        }
    }
}

@Composable
private fun PlaybackScreen(
    state: ViewerUiState,
    onSelectCamera: (String) -> Unit,
    onRefresh: (String?) -> Unit,
    onChangeDay: (Int) -> Unit,
    onToggleRecording: (Boolean, Int) -> Unit,
    onSelectClip: (String) -> Unit
) {
    val cameraId = state.selectedCameraId
    LaunchedEffect(cameraId) {
        if (state.config.validate().isSuccess) onRefresh(cameraId)
    }
    val selectedClip = state.recordings.firstOrNull { it.id == state.selectedRecordingId }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val recording = state.recordingStatus
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (recording?.enabled == true) "Ghi hình đang bật" else "Ghi hình đang tắt",
                        style = MaterialTheme.typography.titleMedium
                    )
                    recording?.let {
                        Text("Drive: ${it.uploadedClips} đã tải lên • ${it.pendingUploads} đang chờ • ${it.failedUploads} lỗi")
                        it.lastUploadError?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error)
                        }
                        Text("Giữ clip cục bộ ${it.localRetentionMinutes} phút • còn ${formatBytes(it.diskFreeBytes)}")
                    }
                    Button(
                        onClick = { onToggleRecording(recording?.enabled != true, recording?.localRetentionMinutes ?: 60) }
                    ) { Text(if (recording?.enabled == true) "Tắt ghi hình" else "Bật ghi hình") }
                    Text("Clip đầu tiên xuất hiện sau khi segment 60 giây hoàn tất.")
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { onChangeDay(-1) }) { Text("Ngày trước") }
                Text(formatRecordingDay(state.playbackDayStartMs), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { onChangeDay(1) }) { Text("Ngày sau") }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.cameras, key = { it.id }) { camera ->
                    FilterChip(
                        selected = camera.id == cameraId,
                        onClick = { onSelectCamera(camera.id) },
                        label = { Text(camera.name) }
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onRefresh(cameraId) }) { Text("Làm mới clip") }
                Text("${state.recordings.size} clip")
            }
        }
        selectedClip?.let { clip ->
            item {
                PlaybackPlayer(
                    url = state.config.recordingUrl(clip.id),
                    apiToken = state.config.apiToken
                )
            }
        }
        if (state.recordings.isEmpty()) {
            item { Text("Chưa có clip hoàn tất cho camera này.") }
        }
        items(state.recordings, key = { it.id }) { clip ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(formatRecordingTime(clip.startedAtMs), style = MaterialTheme.typography.titleMedium)
                    Text("${formatDuration(clip.durationMs)} • ${formatBytes(clip.sizeBytes)}")
                    Text(uploadStateText(clip.uploadState, clip.localState))
                    clip.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = { onSelectClip(clip.id) }) { Text("Phát clip") }
                }
            }
        }
    }
}

private fun formatRecordingTime(value: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(value))

private fun formatRecordingDay(value: Long): String =
    SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(value))

private fun uploadStateText(uploadState: String, localState: String): String = when (uploadState) {
    "UPLOADED" -> if (localState == "AVAILABLE") "Đã lưu Drive • còn bản trên máy" else "Đã lưu Drive • phát qua bộ nhớ đệm"
    "UPLOADING" -> "Đang tải lên Google Drive"
    "FAILED" -> "Tải Drive lỗi • Gateway sẽ tự thử lại"
    else -> "Đang chờ tải lên Google Drive"
}

private fun formatDuration(value: Long?): String {
    if (value == null) return "Chưa rõ thời lượng"
    val seconds = value / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun StorageScreen(
    state: ViewerUiState,
    onAdd: (GoogleDriveMutation) -> Unit,
    onRefresh: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "OAuth token được gửi một lần tới Gateway, lưu trong rclone.conf và không lưu trên Viewer.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        item { Button(onClick = { showAddDialog = true }) { Text("Thêm Google Drive") } }
        if (state.drives.isEmpty()) {
            item { Text("Chưa có tài khoản Google Drive.") }
        }
        items(state.drives, key = { it.id }) { drive ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(drive.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("Remote: ${drive.id} • ${drive.status}${if (drive.active) " • đang dùng" else ""}")
                    drive.quota?.let { quota ->
                        Text("Đã dùng ${formatBytes(quota.used)} / ${formatBytes(quota.total)} • còn ${formatBytes(quota.free)}")
                    }
                    drive.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onRefresh(drive.id) }) { Text("Kiểm tra") }
                        TextButton(onClick = { onDelete(drive.id) }) { Text("Xóa") }
                    }
                }
            }
        }
    }
    if (showAddDialog) {
        AddDriveDialog(
            onDismiss = { showAddDialog = false },
            onSave = {
                onAdd(it)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun AddDriveDialog(onDismiss: () -> Unit, onSave: (GoogleDriveMutation) -> Unit) {
    var id by remember { mutableStateOf("drive01") }
    var displayName by remember { mutableStateOf("Google Drive 01") }
    var oauthToken by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thêm Google Drive") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Dán JSON token được tạo bởi rclone authorize. Token chỉ được chuyển tới Gateway sau khi bấm Lưu.")
                OutlinedTextField(
                    id,
                    { id = it.lowercase().filter { char -> char.isLetterOrDigit() || char == '_' || char == '-' } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Mã remote") },
                    singleLine = true
                )
                OutlinedTextField(
                    displayName,
                    { displayName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tên hiển thị") },
                    singleLine = true
                )
                OutlinedTextField(
                    oauthToken,
                    { oauthToken = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("OAuth token JSON") },
                    visualTransformation = PasswordVisualTransformation(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                enabled = id.isNotBlank() && oauthToken.isNotBlank(),
                onClick = { onSave(GoogleDriveMutation(id.trim(), displayName.trim(), oauthToken.trim())) }
            ) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}

private fun formatBytes(value: Long?): String {
    if (value == null) return "—"
    val gib = value.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) String.format("%.1f GB", gib) else String.format("%.1f MB", value / (1024.0 * 1024.0))
}

@Composable
private fun LiveScreen(state: ViewerUiState, onSelect: (String) -> Unit) {
    if (state.config.validate().isFailure) {
        Placeholder("Mở Cài đặt để nhập IP Gateway và API token.")
        return
    }
    if (state.cameras.isEmpty()) {
        Placeholder("Chưa có camera. Mở Thiết bị để quét LAN hoặc thêm camera.")
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.cameras, key = { it.id }) { camera ->
            FilterChip(
                selected = camera.id == state.selectedCameraId,
                onClick = { onSelect(camera.id) },
                label = { Text(camera.name) }
            )
        }
    }
    state.selectedCamera?.let { camera ->
        Text(camera.name, style = MaterialTheme.typography.titleMedium)
        RtspPlayer(state.config.relayUrl(camera.relayPath))
        Text("RTSP/TCP • ${camera.host}:${camera.port} → ${camera.relayPath}")
    }
}

@Composable
private fun DevicesScreen(
    state: ViewerUiState,
    onRefresh: () -> Unit,
    onScan: () -> Unit,
    onSave: (CameraMutation) -> Unit,
    onDelete: (String) -> Unit
) {
    var editor by remember { mutableStateOf<CameraEditorSeed?>(null) }
    var deleteCamera by remember { mutableStateOf<CameraSummary?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onScan, enabled = !state.loading) { Text("Quét LAN") }
        OutlinedButton(onClick = onRefresh, enabled = !state.loading) { Text("Làm mới") }
        OutlinedButton(
            onClick = { editor = CameraEditorSeed(id = nextCameraId(state.cameras)) },
            enabled = !state.loading
        ) { Text("Thêm thủ công") }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Text("Camera đã cấu hình", style = MaterialTheme.typography.titleMedium) }
        items(state.cameras, key = { "camera-${it.id}" }) { camera ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(camera.name, style = MaterialTheme.typography.titleMedium)
                    Text("${camera.host}:${camera.port} • relay /${camera.relayPath}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { editor = CameraEditorSeed.from(camera) }) { Text("Sửa") }
                        TextButton(onClick = { deleteCamera = camera }) { Text("Xóa") }
                    }
                }
            }
        }
        if (state.candidates.isNotEmpty()) {
            item { Text("Thiết bị tìm thấy", style = MaterialTheme.typography.titleMedium) }
            val groups = state.candidates.groupBy { it.host }.toSortedMap()
            items(groups.entries.toList(), key = { "candidate-${it.key}" }) { (host, entries) ->
                val ports = entries.map { it.port }.distinct().sorted()
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(host, style = MaterialTheme.typography.titleMedium)
                            Text("Cổng: ${ports.joinToString()}")
                        }
                        Button(
                            onClick = {
                                editor = CameraEditorSeed(
                                    id = nextCameraId(state.cameras),
                                    host = host,
                                    port = if (554 in ports) "554" else ports.first().toString()
                                )
                            }
                        ) { Text("Thêm") }
                    }
                }
            }
        }
    }
    editor?.let { seed ->
        CameraEditorDialog(
            seed = seed,
            onDismiss = { editor = null },
            onSave = { onSave(it); editor = null }
        )
    }
    deleteCamera?.let { camera ->
        AlertDialog(
            onDismissRequest = { deleteCamera = null },
            title = { Text("Xóa ${camera.name}?") },
            text = { Text("Gateway sẽ dừng relay của camera này. Video hiện có không bị xóa.") },
            confirmButton = {
                Button(onClick = { onDelete(camera.id); deleteCamera = null }) { Text("Xóa") }
            },
            dismissButton = { TextButton(onClick = { deleteCamera = null }) { Text("Hủy") } }
        )
    }
}

@Composable
private fun CameraEditorDialog(
    seed: CameraEditorSeed,
    onDismiss: () -> Unit,
    onSave: (CameraMutation) -> Unit
) {
    var id by remember(seed) { mutableStateOf(seed.id) }
    var name by remember(seed) { mutableStateOf(seed.name.ifBlank { seed.id }) }
    var host by remember(seed) { mutableStateOf(seed.host) }
    var port by remember(seed) { mutableStateOf(seed.port) }
    var username by remember(seed) { mutableStateOf(seed.username.ifBlank { "admin" }) }
    var password by remember(seed) { mutableStateOf("") }
    var mainPath by remember(seed) { mutableStateOf(seed.mainPath.ifBlank { "Streaming/Channels/101" }) }
    var subPath by remember(seed) { mutableStateOf(seed.subPath.ifBlank { "Streaming/Channels/102" }) }
    var recordEnabled by remember(seed) { mutableStateOf(seed.recordEnabled) }
    var motionEnabled by remember(seed) { mutableStateOf(seed.motionEnabled) }
    val validPort = port.toIntOrNull()?.let { it in 1..65535 } == true
    val valid = id.isNotBlank() && host.isNotBlank() && validPort && mainPath.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (seed.editing) "Sửa camera" else "Thêm camera") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(id, { id = it.lowercase() }, label = { Text("ID") }, enabled = !seed.editing) }
                item { OutlinedTextField(name, { name = it }, label = { Text("Tên camera") }) }
                item { OutlinedTextField(host, { host = it }, label = { Text("IP camera") }) }
                item {
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit) }, label = { Text("RTSP port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                item { OutlinedTextField(username, { username = it }, label = { Text("Username") }) }
                item {
                    OutlinedTextField(
                        password, { password = it },
                        label = { Text(if (seed.editing) "Password mới (để trống nếu giữ nguyên)" else "Password") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
                item { OutlinedTextField(mainPath, { mainPath = it }, label = { Text("Main RTSP path") }) }
                item { OutlinedTextField(subPath, { subPath = it }, label = { Text("Sub RTSP path") }) }
                item { ToggleRow("Ghi hình", recordEnabled) { recordEnabled = it } }
                item { ToggleRow("Phát hiện chuyển động", motionEnabled) { motionEnabled = it } }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        CameraMutation(
                            id = id,
                            name = name.ifBlank { id },
                            host = host.trim(),
                            port = port.toInt(),
                            username = username,
                            password = password.takeIf { it.isNotEmpty() },
                            mainPath = mainPath.trim('/'),
                            subPath = subPath.trim('/'),
                            relayPath = seed.relayPath.ifBlank { id },
                            recordEnabled = recordEnabled,
                            motionEnabled = motionEnabled
                        )
                    )
                }
            ) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}

@Composable
private fun SettingsScreen(
    state: ViewerUiState,
    onSave: (String, String, String, String) -> Unit,
    onRefresh: () -> Unit
) {
    var host by remember(state.config.host) { mutableStateOf(state.config.host) }
    var apiPort by remember(state.config.apiPort) { mutableStateOf(state.config.apiPort.toString()) }
    var rtspPort by remember(state.config.rtspPort) { mutableStateOf(state.config.rtspPort.toString()) }
    var token by remember(state.config.apiToken) { mutableStateOf(state.config.apiToken) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Kết nối Termux Gateway", style = MaterialTheme.typography.titleMedium) }
        item { OutlinedTextField(host, { host = it }, modifier = Modifier.fillMaxWidth(), label = { Text("IP LAN hoặc Tailscale") }) }
        item {
            OutlinedTextField(
                apiPort, { apiPort = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("API port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
        item {
            OutlinedTextField(
                rtspPort, { rtspPort = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("RTSP port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
        item {
            OutlinedTextField(
                token, { token = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API token") },
                visualTransformation = PasswordVisualTransformation()
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(host, apiPort, rtspPort, token) }) { Text("Lưu và kết nối") }
                OutlinedButton(onClick = onRefresh) { Text("Kiểm tra") }
            }
        }
        item { Text("API token được mã hóa bằng Android Keystore và không gửi cho dịch vụ nào ngoài Gateway đã cấu hình.") }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Placeholder(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

private data class CameraEditorSeed(
    val id: String,
    val name: String = "",
    val host: String = "",
    val port: String = "554",
    val username: String = "admin",
    val mainPath: String = "Streaming/Channels/101",
    val subPath: String = "Streaming/Channels/102",
    val relayPath: String = "",
    val recordEnabled: Boolean = true,
    val motionEnabled: Boolean = false,
    val editing: Boolean = false
) {
    companion object {
        fun from(camera: CameraSummary) = CameraEditorSeed(
            id = camera.id,
            name = camera.name,
            host = camera.host,
            port = camera.port.toString(),
            username = camera.username,
            mainPath = camera.mainPath,
            subPath = camera.subPath,
            relayPath = camera.relayPath,
            recordEnabled = camera.recordingEnabled,
            motionEnabled = camera.motionEnabled,
            editing = true
        )
    }
}

private fun nextCameraId(cameras: List<CameraSummary>): String {
    var number = cameras.size + 1
    while (cameras.any { it.id == "camera%02d".format(number) }) number++
    return "camera%02d".format(number)
}
