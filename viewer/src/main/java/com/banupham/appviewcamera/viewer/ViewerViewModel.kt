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
import com.banupham.appviewcamera.viewer.api.RecordingClip
import com.banupham.appviewcamera.viewer.api.RecordingStatus
import com.banupham.appviewcamera.viewer.api.StorageSummary
import com.banupham.appviewcamera.viewer.security.AndroidKeystoreCredentialCipher
import com.banupham.appviewcamera.viewer.settings.GatewayConfig
import com.banupham.appviewcamera.viewer.settings.GatewayConfigStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

data class ViewerUiState(
    val config: GatewayConfig = GatewayConfig(),
    val gatewayStatus: GatewayStatus? = null,
    val cameras: List<CameraSummary> = emptyList(),
    val candidates: List<DiscoveryCandidate> = emptyList(),
    val drives: List<GoogleDriveAccount> = emptyList(),
    val storageSummary: StorageSummary? = null,
    val recordingStatus: RecordingStatus? = null,
    val recordings: List<RecordingClip> = emptyList(),
    val selectedRecordingId: String? = null,
    val playbackDayStartMs: Long = startOfToday(),
    val selectedCameraId: String? = null,
    val loading: Boolean = false,
    val message: String? = null
) {
    val selectedCamera: CameraSummary?
        get() = cameras.firstOrNull { it.id == selectedCameraId }
}

class ViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val configStore = GatewayConfigStore(application, AndroidKeystoreCredentialCipher())
    private val _state = MutableStateFlow(ViewerUiState(config = configStore.load()))
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
                val status = async { api.status() }
                val cameras = async { api.cameras() }
                val drives = async { api.drives() }
                val recording = async { api.recordingStatus() }
                val storage = async { api.storageSummary() }
                RefreshPayload(status.await(), cameras.await(), drives.await(), recording.await(), storage.await())
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
                        message = null
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(loading = false, gatewayStatus = null, message = error.message ?: "Kết nối thất bại")
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
            val dayStart = _state.value.playbackDayStartMs
            val clips = api.recordings(cameraId, dayStart, dayStart + DAY_MS - 1)
            _state.update { current ->
                current.copy(
                    recordings = clips,
                    selectedRecordingId = current.selectedRecordingId?.takeIf { id -> clips.any { it.id == id } }
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
                selectedRecordingId = null
            )
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
            if (current.recordings.any { it.id == recordingId }) current.copy(selectedRecordingId = recordingId) else current
        }
    }

    fun protectRecording(recordingId: String, protected: Boolean) =
        launchApiAction(if (protected) "Đang bảo vệ clip…" else "Đang bỏ bảo vệ clip…") { api ->
            api.protectRecording(recordingId, protected)
            val current = _state.value
            val clips = api.recordings(
                current.selectedCameraId,
                current.playbackDayStartMs,
                current.playbackDayStartMs + DAY_MS - 1
            )
            _state.update {
                it.copy(
                    recordings = clips,
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
                .onFailure { error -> _state.update { it.copy(loading = false, message = error.message ?: "Thao tác thất bại") } }
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
