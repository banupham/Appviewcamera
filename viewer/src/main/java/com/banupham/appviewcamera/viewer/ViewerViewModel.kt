package com.banupham.appviewcamera.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.banupham.appviewcamera.viewer.api.CameraSummary
import com.banupham.appviewcamera.viewer.api.GatewayStatus
import com.banupham.appviewcamera.viewer.api.DiscoveryCandidate
import com.banupham.appviewcamera.viewer.api.CameraMutation
import com.banupham.appviewcamera.viewer.api.HttpGatewayApi
import com.banupham.appviewcamera.viewer.api.GoogleDriveAccount
import com.banupham.appviewcamera.viewer.api.GoogleDriveMutation
import com.banupham.appviewcamera.viewer.api.RecordingStatus
import com.banupham.appviewcamera.viewer.api.StorageSummary
import com.banupham.appviewcamera.viewer.api.PlaybackDay
import com.banupham.appviewcamera.viewer.api.PlaybackItem
import com.banupham.appviewcamera.viewer.security.AndroidKeystoreCredentialCipher
import com.banupham.appviewcamera.viewer.settings.GatewayConfig
import com.banupham.appviewcamera.viewer.settings.GatewayConfigStore
import com.banupham.appviewcamera.viewer.settings.PairingUriParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.text.SimpleDateFormat
import java.io.IOException

data class ViewerUiState(
    val config: GatewayConfig = GatewayConfig(),
    val gatewayStatus: GatewayStatus? = null,
    val cameras: List<CameraSummary> = emptyList(),
    val candidates: List<DiscoveryCandidate> = emptyList(),
    val drives: List<GoogleDriveAccount> = emptyList(),
    val storageSummary: StorageSummary? = null,
    val recordingStatus: RecordingStatus? = null,
    val playbackDays: List<PlaybackDay> = emptyList(),
    val playbackItems: List<PlaybackItem> = emptyList(),
    val selectedPlaybackItemId: String? = null,
    val playbackDayStartMs: Long = startOfToday(),
    val selectedCameraId: String? = null,
    val loading: Boolean = false,
    val gatewayConnectionError: String? = null,
    val message: String? = null
) {
    val selectedCamera: CameraSummary?
        get() = cameras.firstOrNull { it.id == selectedCameraId }
}

class ViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val configStore = GatewayConfigStore(application, AndroidKeystoreCredentialCipher())
    private val _state = MutableStateFlow(
        runCatching { ViewerUiState(config = configStore.load()) }
            .getOrElse {
                ViewerUiState(message = "Không đọc được cài đặt cũ; hãy cấu hình lại Gateway")
            }
    )
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null

    init {
        if (_state.value.config.validate().isSuccess) refresh()
    }

    fun saveConfig(host: String, apiPort: String, rtspPort: String, token: String) {
        val config = GatewayConfig(
            host = host,
            apiPort = apiPort.toIntOrNull() ?: -1,
            rtspPort = rtspPort.toIntOrNull() ?: -1,
            apiToken = token
        )
        val validated = config.validate().getOrElse { error ->
            _state.update { it.copy(message = error.message ?: "Cấu hình không hợp lệ") }
            return
        }
        runCatching { configStore.save(validated) }
            .onFailure { error ->
                _state.update { it.copy(message = "Không lưu được API token: ${error.message}") }
                return
            }
        _state.update { it.copy(config = validated, message = "Đã lưu cấu hình Gateway") }
        refresh()
    }

    fun applyPairingUri(value: String) {
        val config = PairingUriParser.parse(value).getOrElse { error ->
            _state.update { it.copy(message = error.message ?: "Chuỗi ghép nối không hợp lệ") }
            return
        }
        _state.update {
            it.copy(
                config = config,
                gatewayStatus = null,
                gatewayConnectionError = null,
                message = "Đã nhận cấu hình từ QR; bấm Lưu và kết nối để kiểm tra Gateway"
            )
        }
    }

    fun refresh() {
        val config = _state.value.config.validate().getOrElse { error ->
            _state.update { it.copy(message = error.message) }
            return
        }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            runCatching {
                val api = HttpGatewayApi(config)
                RefreshPayload(
                    gatewayStatus = api.status(),
                    cameras = api.cameras(),
                    drives = api.drives(),
                    recordingStatus = api.recordingStatus(),
                    storageSummary = api.storageSummary()
                )
            }.onSuccess { payload ->
                val gatewayStatus = payload.gatewayStatus
                val cameras = payload.cameras
                _state.update { current ->
                    val selected = current.selectedCameraId?.takeIf { id -> cameras.any { it.id == id } }
                        ?: cameras.firstOrNull { it.enabled }?.id
                    current.copy(
                        gatewayStatus = gatewayStatus,
                        cameras = cameras,
                        drives = payload.drives,
                        storageSummary = payload.storageSummary,
                        recordingStatus = payload.recordingStatus,
                        selectedCameraId = selected,
                        loading = false,
                        gatewayConnectionError = null,
                        message = null
                    )
                }
            }.onFailure { error ->
                _state.update {
                    val networkFailure = error is IOException
                    val text = if (networkFailure) {
                        "Không kết nối được Gateway: ${error.message ?: "không rõ lỗi"}"
                    } else {
                        error.message ?: "Gateway từ chối yêu cầu"
                    }
                    it.copy(
                        loading = false,
                        gatewayStatus = null,
                        gatewayConnectionError = text.takeIf { networkFailure },
                        message = text
                    )
                }
            }
        }
    }

    fun selectCamera(cameraId: String) {
        _state.update { current ->
            if (current.cameras.any { it.id == cameraId }) current.copy(selectedCameraId = cameraId) else current
        }
    }

    fun scanCameras() = launchApiAction("Đang quét camera trong LAN…") { api ->
        val candidates = api.scanCameras()
        _state.update { it.copy(candidates = candidates, loading = false, message = "Tìm thấy ${candidates.map { item -> item.host }.distinct().size} thiết bị") }
    }

    fun saveCamera(camera: CameraMutation) = launchApiAction("Đang lưu camera…") { api ->
        api.saveCamera(camera)
        val cameras = api.cameras()
        _state.update { it.copy(cameras = cameras, selectedCameraId = camera.id, loading = false, message = "Đã lưu ${camera.name}") }
    }

    fun deleteCamera(cameraId: String) = launchApiAction("Đang xóa camera…") { api ->
        api.deleteCamera(cameraId)
        val cameras = api.cameras()
        _state.update { current ->
            current.copy(
                cameras = cameras,
                selectedCameraId = current.selectedCameraId?.takeIf { id -> cameras.any { it.id == id } }
                    ?: cameras.firstOrNull()?.id,
                loading = false,
                message = "Đã xóa camera"
            )
        }
    }

    fun addDrive(drive: GoogleDriveMutation) = launchApiAction("Đang lưu tài khoản Drive…") { api ->
        api.addDrive(drive)
        val drives = api.drives()
        _state.update { it.copy(drives = drives, loading = false, message = "Đã lưu ${drive.displayName}") }
    }

    fun authorizeDrive(remoteId: String, displayName: String) =
        launchApiAction("Đang chuẩn bị đăng nhập Google…") { api ->
            DriveOAuthCoordinator(getApplication(), api).authorize(remoteId, displayName)
            val account = api.refreshDrive(remoteId)
            _state.update {
                it.copy(
                    drives = api.drives(),
                    storageSummary = api.storageSummary(),
                    loading = false,
                    message = if (account.status == "ONLINE") {
                        "Đã kết nối Google Drive"
                    } else {
                        account.lastError ?: "Đã lưu tài khoản nhưng chưa kiểm tra được Internet"
                    }
                )
            }
        }

    fun refreshDrive(driveId: String) = launchApiAction("Đang kiểm tra Google Drive…") { api ->
        api.refreshDrive(driveId)
        val drives = api.drives()
        val account = drives.firstOrNull { it.id == driveId }
        val message = if (account?.status == "ONLINE") "Google Drive hoạt động" else account?.lastError ?: "Không kết nối được Drive"
        _state.update { it.copy(drives = drives, loading = false, message = message) }
    }

    fun deleteDrive(driveId: String) = launchApiAction("Đang xóa tài khoản Drive…") { api ->
        api.deleteDrive(driveId)
        _state.update { it.copy(drives = api.drives(), loading = false, message = "Đã xóa tài khoản Drive") }
    }

    fun loadRecordings(cameraId: String? = _state.value.selectedCameraId) =
        launchApiAction("Đang đọc danh sách clip…") { api ->
            if (cameraId == null) {
                _state.update {
                    it.copy(
                        playbackDays = emptyList(),
                        playbackItems = emptyList(),
                        selectedPlaybackItemId = null,
                        loading = false,
                        message = "Chưa có camera để xem lại"
                    )
                }
                return@launchApiAction
            }
            val dayStart = _state.value.playbackDayStartMs
            val days = api.playbackDays(cameraId)
            val clips = api.playbackTimeline(cameraId, dayStart, dayStart + DAY_MS)
            _state.update { current ->
                current.copy(
                    playbackDays = days,
                    playbackItems = clips,
                    selectedPlaybackItemId = current.selectedPlaybackItemId?.takeIf { id -> clips.any { it.id == id } }
                        ?: clips.firstOrNull()?.id,
                    loading = false,
                    message = if (clips.isEmpty()) "Chưa có clip hoàn tất" else null
                )
            }
        }

    fun changePlaybackDay(deltaDays: Int) {
        _state.update { current ->
            current.copy(
                playbackDayStartMs = current.playbackDayStartMs + deltaDays * DAY_MS,
                selectedPlaybackItemId = null
            )
        }
        loadRecordings()
    }

    fun selectPlaybackDay(day: String) {
        val parsed = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(day)?.time
                ?: error("Ngày không hợp lệ")
        }.getOrElse { error ->
            _state.update { it.copy(message = error.message ?: "Ngày không hợp lệ") }
            return
        }
        _state.update {
            it.copy(playbackDayStartMs = parsed, selectedPlaybackItemId = null)
        }
        loadRecordings()
    }

    fun setRecordingEnabled(enabled: Boolean, retentionMinutes: Int = 60) =
        launchApiAction(if (enabled) "Đang bật ghi hình…" else "Đang tắt ghi hình…") { api ->
            val status = api.updateRecording(enabled, retentionMinutes)
            _state.update {
                it.copy(
                    recordingStatus = status,
                    loading = false,
                    message = if (enabled) "Đã bật ghi hình; clip đầu tiên có sau khoảng 60 giây" else "Đã tắt ghi hình"
                )
            }
        }

    fun activateDrive(driveId: String) = launchApiAction("Đang chuyển Google Drive…") { api ->
        api.activateDrive(driveId)
        _state.update {
            it.copy(drives = api.drives(), loading = false, message = "Đã chọn tài khoản Drive")
        }
    }

    fun selectRecording(recordingId: String) {
        _state.update { current ->
            if (current.playbackItems.any { it.id == recordingId }) {
                current.copy(selectedPlaybackItemId = recordingId)
            } else current
        }
    }

    fun protectRecording(recordingId: String, protected: Boolean) =
        launchApiAction(if (protected) "Đang bảo vệ clip…" else "Đang bỏ bảo vệ clip…") { api ->
            api.protectRecording(recordingId, protected)
            val current = _state.value
            val cameraId = current.selectedCameraId ?: return@launchApiAction
            val clips = api.playbackTimeline(
                cameraId,
                current.playbackDayStartMs,
                current.playbackDayStartMs + DAY_MS
            )
            _state.update {
                it.copy(
                    playbackItems = clips,
                    loading = false,
                    message = if (protected) "Clip đã được bảo vệ" else "Đã bỏ bảo vệ clip"
                )
            }
        }

    private fun launchApiAction(progress: String, block: suspend (HttpGatewayApi) -> Unit) {
        val config = _state.value.config.validate().getOrElse { error ->
            _state.update { it.copy(message = error.message) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = progress) }
            runCatching { block(HttpGatewayApi(config)) }
                .onSuccess { _state.update { it.copy(gatewayConnectionError = null) } }
                .onFailure { error ->
                    _state.update {
                        val text = error.message ?: "Thao tác thất bại"
                        it.copy(
                            loading = false,
                            gatewayConnectionError = if (error is IOException) {
                                "Không kết nối được Gateway: $text"
                            } else {
                                it.gatewayConnectionError
                            },
                            message = text
                        )
                    }
                }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private data class RefreshPayload(
    val gatewayStatus: GatewayStatus,
    val cameras: List<CameraSummary>,
    val drives: List<GoogleDriveAccount>,
    val recordingStatus: RecordingStatus,
    val storageSummary: StorageSummary
)
