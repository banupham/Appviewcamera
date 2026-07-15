package com.banupham.appviewcamera.viewer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.banupham.appviewcamera.viewer.api.CameraMutation
import com.banupham.appviewcamera.viewer.api.CameraSummary
import com.banupham.appviewcamera.viewer.settings.GatewayConfig
import com.banupham.appviewcamera.viewer.player.RtspPlayer
import com.banupham.appviewcamera.viewer.player.PlaybackPlayer
import com.banupham.appviewcamera.viewer.playback.PlaybackSourceSelector
import com.banupham.appviewcamera.viewer.live.MultiCameraLiveScreen
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var pendingPairingUri by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPairingUri = intent?.dataString
        setContent {
            MaterialTheme {
                ViewerScreen(
                    pairingUri = pendingPairingUri,
                    onPairingConsumed = { pendingPairingUri = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingPairingUri = intent.dataString
    }
}

@Composable
private fun ViewerScreen(
    pairingUri: String? = null,
    onPairingConsumed: () -> Unit = {},
    viewModel: ViewerViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedSection by remember { mutableStateOf(ViewerSection.LIVE) }
    LaunchedEffect(pairingUri) {
        pairingUri?.let {
            selectedSection = ViewerSection.SETTINGS
            viewModel.applyPairingUri(it)
            onPairingConsumed()
        }
    }
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
                state.currentGatewayId?.let {
                    Text("${state.config.name}: ${state.config.status}")
                }
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            state.gatewayConnectionError?.let { error ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Gateway đang ngoại tuyến", style = MaterialTheme.typography.titleMedium)
                        Text(error, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = viewModel::refresh, enabled = !state.loading) {
                            Text("Thử kết nối lại")
                        }
                    }
                }
            }
            if (state.loading) CircularProgressIndicator()
            key(state.currentGatewayId) { when (selectedSection) {
                ViewerSection.LIVE -> MultiCameraLiveScreen(state, viewModel::selectCamera)
                ViewerSection.PLAYBACK -> PlaybackScreen(
                    state = state,
                    onSelectCamera = viewModel::selectCamera,
                    onRefresh = viewModel::loadRecordings,
                    onChangeDay = viewModel::changePlaybackDay,
                    onSelectDay = viewModel::selectPlaybackDay,
                    onToggleRecording = viewModel::setRecordingEnabled,
                    onSelectClip = viewModel::selectRecording,
                    onProtectClip = viewModel::protectRecording
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
                    onAdd = viewModel::authorizeDrive,
                    onRefresh = viewModel::refreshDrive,
                    onActivate = viewModel::activateDrive,
                    onDelete = viewModel::deleteDrive,
                    onAddYouTube = viewModel::authorizeYouTube,
                    onReconnectYouTube = viewModel::reconnectYouTube,
                    onDeleteYouTube = viewModel::deleteYouTubeAccount
                )
                ViewerSection.SETTINGS -> SettingsScreen(
                    state,
                    viewModel::saveConfig,
                    viewModel::applyPairingUri,
                    viewModel::selectGateway,
                    viewModel::beginAddGateway,
                    viewModel::beginEditGateway,
                    viewModel::cancelGatewayDraft,
                    viewModel::renameGateway,
                    viewModel::checkGateway,
                    viewModel::deleteGateway,
                    viewModel::confirmPairingUpdate,
                    viewModel::dismissPairingConflict
                )
            } }
        }
    }
}

@Composable
private fun PlaybackScreen(
    state: ViewerUiState,
    onSelectCamera: (String) -> Unit,
    onRefresh: (String?) -> Unit,
    onChangeDay: (Int) -> Unit,
    onSelectDay: (String) -> Unit,
    onToggleRecording: (Boolean, Int) -> Unit,
    onSelectClip: (String) -> Unit,
    onProtectClip: (String, Boolean) -> Unit
) {
    val cameraId = state.selectedCameraId
    LaunchedEffect(cameraId) {
        if (state.config.validate().isSuccess) onRefresh(cameraId)
    }
    val selectedClip = state.playbackItems.firstOrNull { it.id == state.selectedPlaybackItemId }
    val uriHandler = LocalUriHandler.current
    var failedPlaybackItemId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedClip?.id) { failedPlaybackItemId = null }
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
        if (state.playbackDays.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.playbackDays, key = { it.day }) { day ->
                        FilterChip(
                            selected = day.day == formatPlaybackApiDay(state.playbackDayStartMs),
                            onClick = { onSelectDay(day.day) },
                            label = { Text("${day.day} (${day.itemCount})") }
                        )
                    }
                }
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
                Text("${state.playbackItems.size} clip")
            }
        }
        selectedClip?.let { clip ->
            val effectiveSource = PlaybackSourceSelector.select(
                clip, primaryFailed = failedPlaybackItemId == clip.id
            )
            when (effectiveSource) {
                "LOCAL_CACHE", "DRIVE_READY" -> item {
                    PlaybackPlayer(
                        url = state.config.playbackStreamUrl(
                            clip.id,
                            if (effectiveSource == "LOCAL_CACHE") "local" else "drive"
                        ),
                        apiToken = state.config.apiToken,
                        onPlaybackError = {
                            if (clip.youtubeAvailable) failedPlaybackItemId = clip.id
                        }
                    )
                }
                "YOUTUBE_READY" -> item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Bản YouTube Private sẵn sàng")
                            Text("Cần đăng nhập đúng tài khoản Google được cấp quyền.")
                            Button(onClick = {
                                val videoId = clip.youtubeVideoId ?: return@Button
                                uriHandler.openUri(
                                    "https://www.youtube.com/watch?v=$videoId&t=${clip.youtubeStartOffsetSeconds}s"
                                )
                            }) { Text("Mở YouTube đúng thời điểm") }
                        }
                    }
                }
            }
        }
        if (state.playbackItems.isEmpty()) {
            item { Text("Chưa có clip hoàn tất cho camera này.") }
        }
        items(state.playbackItems, key = { it.id }) { clip ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(formatRecordingTime(clip.startTime), style = MaterialTheme.typography.titleMedium)
                    Text("${formatDuration(clip.duration)} • ${formatBytes(clip.sizeBytes)}")
                    Text(playbackSourceText(clip))
                    if (clip.motion) Text("Có chuyển động • tự động bảo vệ")
                    clip.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (clip.playable) {
                        OutlinedButton(onClick = { onSelectClip(clip.id) }) {
                            Text(if (clip.preferredSource == "YOUTUBE_READY") "Xem bản YouTube" else "Phát clip")
                        }
                    } else {
                        Text("Chưa có nguồn sẵn sàng", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { onProtectClip(clip.id, !clip.protected) }) {
                        Text(if (clip.protected) "Bỏ bảo vệ" else "Bảo vệ clip")
                    }
                }
            }
        }
    }
}

private fun formatRecordingTime(value: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(value))

private fun formatRecordingDay(value: Long): String =
    SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(value))

private fun formatPlaybackApiDay(value: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(value))

private fun playbackSourceText(clip: com.banupham.appviewcamera.viewer.api.PlaybackItem): String = when {
    clip.localAvailable -> "Sẵn sàng từ cache Gateway"
    clip.driveAvailable -> "Sẵn sàng từ Google Drive"
    clip.youtubeAvailable -> "Sẵn sàng từ YouTube Private"
    clip.status == "PROCESSING" -> "Đang xử lý nguồn lưu trữ"
    else -> "Không có nguồn phát sẵn sàng"
}

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
    onAdd: (String, String) -> Unit,
    onRefresh: (String) -> Unit,
    onActivate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAddYouTube: (String, String, String, String, Int) -> Unit,
    onReconnectYouTube: (String) -> Unit,
    onDeleteYouTube: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showAddYouTubeDialog by remember { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.storageSummary?.let { summary ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Tổng quan lưu trữ", style = MaterialTheme.typography.titleMedium)
                        Text("Drive: ${summary.onlineDriveCount}/${summary.driveCount} online")
                        Text("Tổng ${formatBytes(summary.totalBytes)} • còn ${formatBytes(summary.freeBytes)}")
                        summary.averageBitrateBps?.let { Text("Bitrate ghi hình: ${formatBitrate(it)}") }
                        summary.estimatedDailyBytes?.let { Text("Ước tính mỗi ngày: ${formatBytes(it)}") }
                        Text(
                            summary.estimatedRetentionSeconds?.let { "Có thể lưu khoảng ${formatRetention(it)}" }
                                ?: "Đang thu thập dữ liệu để ước tính số ngày lưu"
                        )
                    }
                }
            }
        }
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
                        if (!drive.active) {
                            OutlinedButton(onClick = { onActivate(drive.id) }) { Text("Sử dụng") }
                        }
                        TextButton(onClick = { onDelete(drive.id) }) { Text("Xóa") }
                    }
                }
            }
        }
        item {
            Text("YouTube Private", style = MaterialTheme.typography.titleLarge)
        }
        state.youtubeStatus?.let { youtube ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (youtube.enabled) "Lưu nền đang bật" else "Lưu nền chưa bật trên Gateway")
                        Text("Batch ${youtube.targetDurationMinutes} phút • ước tính ${youtube.estimatedUploadsPerDay}/${youtube.maxTargetUploadsPerDay} video/ngày")
                        youtube.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        youtube.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        item {
            Text("Google Drive vẫn là kho chính. YouTube chỉ nhận các batch đã DRIVE_READY; token chỉ lưu trên Gateway.")
        }
        item { Button(onClick = { showAddYouTubeDialog = true }) { Text("Thêm YouTube Private") } }
        if (state.youtubeAccounts.isEmpty()) item { Text("Chưa có tài khoản YouTube.") }
        items(state.youtubeAccounts, key = { "youtube-${it.id}" }) { account ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(account.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("${account.id} • ${account.status}")
                    account.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onReconnectYouTube(account.id) }) { Text("Kết nối lại") }
                        TextButton(onClick = { onDeleteYouTube(account.id) }) { Text("Xóa") }
                    }
                }
            }
        }
    }
    if (showAddDialog) {
        AddDriveDialog(
            onDismiss = { showAddDialog = false },
            onSave = {
                onAdd(it.first, it.second)
                showAddDialog = false
            }
        )
    }
    if (showAddYouTubeDialog) {
        AddYouTubeDialog(
            onDismiss = { showAddYouTubeDialog = false },
            oauthConfigured = state.youtubeStatus?.oauthConfigured == true,
            onSave = { id, name, clientId, clientSecret, targetMinutes ->
                onAddYouTube(id, name, clientId, clientSecret, targetMinutes)
                showAddYouTubeDialog = false
            }
        )
    }
}

@Composable
private fun AddYouTubeDialog(
    oauthConfigured: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, Int) -> Unit
) {
    var id by remember { mutableStateOf("youtube01") }
    var displayName by remember { mutableStateOf("YouTube Private 01") }
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var targetMinutes by remember { mutableStateOf(60) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thêm YouTube Private") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Viewer mở trang Google với quyền tối thiểu để upload YouTube. Viewer không nhận hoặc lưu token.")
                OutlinedTextField(
                    id,
                    { id = it.lowercase().filter { char -> char.isLetterOrDigit() || char == '_' || char == '-' } },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Mã tài khoản") }, singleLine = true
                )
                OutlinedTextField(
                    displayName, { displayName = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tên hiển thị") }, singleLine = true
                )
                if (!oauthConfigured) {
                    Text("Nhập OAuth Desktop client một lần. Dữ liệu được gửi thẳng tới Gateway và không lưu trên Viewer.")
                    OutlinedTextField(
                        clientId, { clientId = it.trim() }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("OAuth client ID") }, singleLine = true
                    )
                    OutlinedTextField(
                        clientSecret, { clientSecret = it.trim() }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("OAuth client secret") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
                Text("Độ dài batch mong muốn")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60, 120).forEach { minutes ->
                        FilterChip(
                            selected = targetMinutes == minutes,
                            onClick = { targetMinutes = minutes },
                            label = { Text("$minutes") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = id.isNotBlank() && displayName.isNotBlank() &&
                    (oauthConfigured || (clientId.isNotBlank() && clientSecret.isNotBlank())),
                onClick = { onSave(id.trim(), displayName.trim(), clientId, clientSecret, targetMinutes) }
            ) { Text("Đăng nhập Google") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}

private fun formatBitrate(value: Long): String =
    if (value >= 1_000_000) "%.2f Mbps".format(value / 1_000_000.0) else "%.0f Kbps".format(value / 1_000.0)

private fun formatRetention(seconds: Long): String {
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3_600
    return if (days > 0) "$days ngày $hours giờ" else "$hours giờ"
}

@Composable
private fun AddDriveDialog(onDismiss: () -> Unit, onSave: (Pair<String, String>) -> Unit) {
    var id by remember { mutableStateOf("drive01") }
    var displayName by remember { mutableStateOf("Google Drive 01") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thêm Google Drive") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Viewer sẽ mở trang đăng nhập Google. Token được nhận và lưu trực tiếp tại Gateway, không hiển thị trong Viewer.")
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
            }
        },
        confirmButton = {
            Button(
                enabled = id.isNotBlank() && displayName.isNotBlank(),
                onClick = { onSave(id.trim() to displayName.trim()) }
            ) { Text("Đăng nhập Google") }
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
    if (state.gatewayConnectionError != null || state.gatewayStatus == null) {
        Placeholder("Không kết nối được Gateway. App vẫn hoạt động; hãy kiểm tra Wi‑Fi/Tailscale rồi bấm Thử kết nối lại.")
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
    val availableCandidates = availableDiscoveryCandidates(state.candidates, state.cameras)
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
        if (availableCandidates.isNotEmpty()) {
            item { Text("Thiết bị tìm thấy", style = MaterialTheme.typography.titleMedium) }
            val groups = availableCandidates.groupBy { it.host }.toSortedMap()
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
    onSave: (String, String, String, String, String) -> Unit,
    onPair: (String) -> Unit,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onRename: (String, String) -> Unit,
    onCheck: (String) -> Unit,
    onDelete: (String) -> Unit,
    onConfirmPairingUpdate: () -> Unit,
    onDismissPairingUpdate: () -> Unit
) {
    val draft = state.pairingDraft
    var name by remember(draft) { mutableStateOf(draft?.name.orEmpty()) }
    var host by remember(draft) { mutableStateOf(draft?.host.orEmpty()) }
    var apiPort by remember(draft) { mutableStateOf((draft?.apiPort ?: 8080).toString()) }
    var rtspPort by remember(draft) { mutableStateOf((draft?.rtspPort ?: 8554).toString()) }
    var token by remember(draft) { mutableStateOf(draft?.apiToken.orEmpty()) }
    var renameTarget by remember { mutableStateOf<GatewayConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<GatewayConfig?>(null) }
    val clipboard = LocalClipboardManager.current
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(onPair)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Gateway đã ghép nối", style = MaterialTheme.typography.titleMedium)
                Button(onClick = onAdd) { Text("Thêm Gateway") }
            }
        }
        if (state.gateways.isEmpty()) {
            item { Text("Chưa có Gateway. Hãy quét QR hoặc thêm thủ công.") }
        }
        items(state.gateways, key = { it.id }) { gateway ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(gateway.name, style = MaterialTheme.typography.titleMedium)
                        if (gateway.id == state.currentGatewayId) Text("Đang chọn")
                    }
                    Text("${gateway.status} • ${gateway.cameraCount} camera")
                    Text("${gateway.host}:${gateway.apiPort} • RTSP ${gateway.rtspPort}")
                    Text("Lần kết nối cuối: ${formatLastSeen(gateway.lastSeen)}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSelect(gateway.id) },
                            enabled = gateway.id != state.currentGatewayId
                        ) { Text("Chọn") }
                        OutlinedButton(onClick = { onCheck(gateway.id) }) { Text("Kiểm tra") }
                        OutlinedButton(onClick = { onEdit(gateway.id) }) { Text("Sửa") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { renameTarget = gateway }) { Text("Đổi tên") }
                        TextButton(onClick = { deleteTarget = gateway }) { Text("Xóa khỏi Viewer") }
                    }
                }
            }
        }
        item { Text("Ghép nối nhanh", style = MaterialTheme.typography.titleMedium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        qrScanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Quét QR ghép nối trên Gateway")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false)
                        )
                    }
                ) { Text("Quét QR") }
                OutlinedButton(
                    onClick = {
                        clipboard.getText()?.text?.takeIf(String::isNotBlank)?.let(onPair)
                    }
                ) { Text("Dán chuỗi ghép nối") }
            }
        }
        item { Text("QR mới sẽ thêm Gateway, không ghi đè Gateway cũ. App chỉ lưu sau khi bấm Lưu và kết nối.") }
        if (draft != null) {
            item { Text(if (state.gateways.any { it.id == draft.id }) "Sửa Gateway" else "Gateway mới", style = MaterialTheme.typography.titleMedium) }
            item { OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Tên Gateway") }) }
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
                    Button(onClick = { onSave(name, host, apiPort, rtspPort, token) }) { Text("Lưu và kết nối") }
                    OutlinedButton(onClick = onCancelEdit) { Text("Hủy") }
                }
            }
        }
        item { Text("API token được mã hóa bằng Android Keystore và không gửi cho dịch vụ nào ngoài Gateway đã cấu hình.") }
    }
    state.pairingConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = onDismissPairingUpdate,
            title = { Text("Gateway đã tồn tại") },
            text = { Text("QR có cùng Gateway ID với ${state.gateways.firstOrNull { it.id == conflict.id }?.name}. Cập nhật IP, cổng và token?") },
            confirmButton = { Button(onClick = onConfirmPairingUpdate) { Text("Cập nhật") } },
            dismissButton = { TextButton(onClick = onDismissPairingUpdate) { Text("Hủy") } }
        )
    }
    renameTarget?.let { gateway ->
        var newName by remember(gateway.id) { mutableStateOf(gateway.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Đổi tên Gateway") },
            text = { OutlinedTextField(newName, { newName = it }, label = { Text("Tên") }) },
            confirmButton = {
                Button(onClick = { onRename(gateway.id, newName); renameTarget = null }) { Text("Lưu") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Hủy") } }
        )
    }
    deleteTarget?.let { gateway ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Xóa ${gateway.name} khỏi Viewer?") },
            text = { Text("Chỉ xóa cấu hình trên Viewer. Camera, video, Drive và YouTube trên Gateway không bị xóa.") },
            confirmButton = {
                Button(onClick = { onDelete(gateway.id); deleteTarget = null }) { Text("Xóa khỏi Viewer") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Hủy") } }
        )
    }
}

private fun formatLastSeen(value: Long?): String = value?.let {
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(it))
} ?: "Chưa kết nối"

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
